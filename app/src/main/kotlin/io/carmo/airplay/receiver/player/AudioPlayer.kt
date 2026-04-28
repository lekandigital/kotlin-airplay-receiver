package io.carmo.airplay.receiver.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import io.carmo.airplay.receiver.model.PCMPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class AudioPlayer : Thread("ReceiverAudioPlayer") {

    private val packets = ArrayBlockingQueue<PCMPacket>(MAX_BUFFERED_PACKETS)
    private var track: AudioTrack? = createAudioTrack().also { it.play() }
    @Volatile private var isStopped = false

    fun addPacket(packet: PCMPacket) {
        if (!packets.offer(packet)) {
            packets.poll()?.release()
            if (!packets.offer(packet)) {
                packet.release()
            }
        }
    }

    override fun run() {
        while (!isStopped) {
            try {
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
            flush()
            stop()
            release()
        }
        track = null
    }

    private fun play(packet: PCMPacket) {
        try {
            packet.data.position(0)
            packet.data.limit(packet.size)
            track?.write(packet.data, packet.size, AudioTrack.WRITE_BLOCKING)
        } finally {
            packet.release()
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

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                .setBufferSizeInBytes(minBufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } else {
            @Suppress("DEPRECATION")
            AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNELS, AUDIO_FORMAT, minBufferSize, AudioTrack.MODE_STREAM)
        }
    }

    companion object {
        private const val MAX_BUFFERED_PACKETS = 24
        private const val SAMPLE_RATE = 44100
        private const val CHANNELS = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
