package io.carmo.airplay.receiver.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.os.SystemClock
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

    fun addPacket(packet: PCMPacket) {
        trimBacklog()
        if (!packets.offer(packet)) {
            packets.poll()?.release()
            if (!packets.offer(packet)) {
                packet.release()
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
                packets.poll(250, TimeUnit.MILLISECONDS)?.let(::play)
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
            track?.write(packet.data, packet.size, AudioTrack.WRITE_BLOCKING)
            onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
        } finally {
            packet.release()
        }
    }

    private fun trimBacklog() {
        while (packets.size >= MAX_QUEUED_PACKETS) {
            packets.poll()?.release() ?: return
        }
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
        return audioTrack
    }

    companion object {
        private const val DEFAULT_VOLUME = 1.0f
        private const val MIN_VOLUME = 0.0f
        private const val MAX_VOLUME = 1.0f
        private const val MAX_BUFFERED_PACKETS = 64
        private const val MAX_QUEUED_PACKETS = 48
        private const val PREBUFFER_PACKETS = 12
        private const val PREBUFFER_WAIT_MS = 10L
        private const val AUDIO_BUFFER_MULTIPLIER = 8
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_FRAME = 4
    }
}
