package io.carmo.airplay.receiver.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import io.carmo.airplay.receiver.model.PCMPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AudioPlayer(
    initialVolume: Float = DEFAULT_VOLUME,
    private val onLatencySample: (Long) -> Unit = {}
) : Thread("ReceiverAudioPlayer") {

    private val packets = ArrayBlockingQueue<PCMPacket>(MAX_BUFFERED_PACKETS)
    @Volatile private var volume = initialVolume.coerceIn(MIN_VOLUME, MAX_VOLUME)
    private var track: AudioTrack? = createAudioTrack()
    @Volatile private var isStopped = false
    private var hasStartedPlayback = false
    private var droppedPackets = 0L
    private var lastDropLogAtMs = 0L
    private var lastUnderrunLogAtMs = 0L

    fun addPacket(packet: PCMPacket) {
        val trimmed = trimBacklog()
        if (trimmed > 0) {
            logDroppedPackets(trimmed)
        }
        if (!packets.offer(packet)) {
            packets.poll()?.release()
            logDroppedPackets(1)
            if (!packets.offer(packet)) {
                packet.release()
                logDroppedPackets(1)
            }
        }
    }

    fun setVolume(volume: Float) {
        this.volume = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        track?.setVolume(this.volume)
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        while (!isStopped) {
            try {
                if (!hasStartedPlayback && packets.size < PREBUFFER_PACKETS) {
                    Thread.sleep(PREBUFFER_WAIT_MS)
                    continue
                }
                val packet = packets.poll(250, TimeUnit.MILLISECONDS)
                if (packet == null) {
                    if (hasStartedPlayback) {
                        logUnderrun()
                    }
                    continue
                }
                play(packet)
            } catch (e: InterruptedException) {
                if (isStopped) {
                    break
                }
            }
        }
    }

    fun stopPlay() {
        isStopped = true
        interrupt()
        drainPackets()
        track?.run {
            try {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    stop()
                }
            } catch (_: Exception) {
            }
            try {
                flush()
            } catch (_: Exception) {
            }
            try {
                release()
            } catch (_: Exception) {
            }
        }
        track = null
    }

    private fun play(packet: PCMPacket) {
        try {
            packet.data.position(0)
            packet.data.limit(packet.size)
            track?.setVolume(volume)
            if (!hasStartedPlayback) {
                track?.play()
                hasStartedPlayback = true
            }
            val written = track?.write(packet.data, packet.size, AudioTrack.WRITE_BLOCKING) ?: 0
            if (written < 0) {
                Log.w(TAG, "AudioTrack write failed with code $written")
            } else if (written != packet.size) {
                Log.w(TAG, "short AudioTrack write: wrote $written of ${packet.size} bytes")
            } else {
                onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
            }
        } finally {
            packet.release()
        }
    }

    private fun trimBacklog(): Int {
        var trimmed = 0
        while (packets.size >= MAX_QUEUED_PACKETS) {
            packets.poll()?.release() ?: return trimmed
            trimmed += 1
        }
        return trimmed
    }

    private fun drainPackets() {
        while (true) {
            packets.poll()?.release() ?: break
        }
    }

    private fun createAudioTrack(): AudioTrack {
        var minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNELS, AUDIO_FORMAT)
        if (minBufferSize <= 0) {
            minBufferSize = SAMPLE_RATE / 10 * 2 * 2
        }
        val bufferSize = minBufferSize * AUDIO_BUFFER_MULTIPLIER

        val audioTrack = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AUDIO_FORMAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNELS)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNELS, AUDIO_FORMAT, bufferSize, AudioTrack.MODE_STREAM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioTrack.setBufferSizeInFrames(bufferSize / BYTES_PER_FRAME)
        }
        audioTrack.setVolume(volume)
        Log.i(
            TAG,
            "AudioTrack ready: minBuffer=$minBufferSize bytes, buffer=$bufferSize bytes, " +
                "prebuffer=$PREBUFFER_PACKETS packets, queueLimit=$MAX_QUEUED_PACKETS packets"
        )
        return audioTrack
    }

    private fun logDroppedPackets(count: Int) {
        droppedPackets += count.toLong()
        val now = SystemClock.elapsedRealtime()
        if (now - lastDropLogAtMs >= AUDIO_LOG_INTERVAL_MS) {
            lastDropLogAtMs = now
            Log.w(TAG, "audio queue pressure: dropped=$droppedPackets queued=${packets.size}")
        }
    }

    private fun logUnderrun() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnderrunLogAtMs >= AUDIO_LOG_INTERVAL_MS) {
            lastUnderrunLogAtMs = now
            Log.w(TAG, "audio underrun: queue empty after playback started")
        }
    }

    companion object {
        private const val TAG = "Receiver-Audio"
        private const val DEFAULT_VOLUME = 1.0f
        private const val MIN_VOLUME = 0.0f
        private const val MAX_VOLUME = 1.0f
        private const val MAX_BUFFERED_PACKETS = 128
        private const val MAX_QUEUED_PACKETS = 96
        private const val PREBUFFER_PACKETS = 24
        private const val PREBUFFER_WAIT_MS = 10L
        private const val AUDIO_BUFFER_MULTIPLIER = 8
        private const val AUDIO_LOG_INTERVAL_MS = 5_000L
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 4
    }
}
