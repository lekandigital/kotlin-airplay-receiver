package io.carmo.airplay.receiver.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import io.carmo.airplay.receiver.model.NALPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class VideoPlayer(private val surface: Surface) : Thread("ReceiverVideoPlayer") {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val packets = ArrayBlockingQueue<NALPacket>(MAX_BUFFERED_FRAMES)
    private var decoder: MediaCodec? = null
    @Volatile private var isStopped = false

    fun addPacket(packet: NALPacket) {
        if (!packets.offer(packet)) {
            packets.poll()?.release()
            if (!packets.offer(packet)) {
                packet.release()
                return
            }
            if (DEBUG_FRAMES) {
                Log.d(TAG, "video queue full; dropped oldest frame")
            }
        }
    }

    override fun run() {
        initDecoder()
        while (!isStopped) {
            try {
                packets.poll(250, TimeUnit.MILLISECONDS)?.let(::decode)
            } catch (e: InterruptedException) {
                if (isStopped) {
                    break
                }
            }
        }
        releaseDecoder()
    }

    fun stopDecode() {
        isStopped = true
        interrupt()
        drainPackets()
    }

    private fun initDecoder() {
        try {
            val videoFormat = MediaFormat.createVideoFormat(MIME_TYPE, VIDEO_WIDTH, VIDEO_HEIGHT)
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            decoder = codec

            val decoderInfo = codec.codecInfo
            Log.i(TAG, "decoder selected = ${decoderInfo.name}")

            if (decoderSupportsAdaptivePlayback(decoderInfo, MIME_TYPE)) {
                videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, VIDEO_WIDTH)
                videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, VIDEO_HEIGHT)
            }

            codec.configure(videoFormat, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun decode(packet: NALPacket) {
        val codec = decoder
        if (codec == null) {
            packet.release()
            return
        }

        try {
            val inputBufferIndex = codec.dequeueInputBuffer(TIMEOUT_USEC)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer == null || packet.size > inputBuffer.capacity()) {
                    if (DEBUG_FRAMES) {
                        Log.d(TAG, "dropping oversized NAL: ${packet.size}")
                    }
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, packet.pts, 0)
                    return
                }

                inputBuffer.clear()
                packet.data.position(0)
                packet.data.limit(packet.size)
                inputBuffer.put(packet.data)
                codec.queueInputBuffer(inputBufferIndex, 0, packet.size, packet.pts, 0)
            } else if (DEBUG_FRAMES) {
                Log.d(TAG, "dequeueInputBuffer failed")
            }

            var outputBufferIndex: Int
            do {
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)
                if (outputBufferIndex >= 0) {
                    codec.releaseOutputBuffer(outputBufferIndex, true)
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && DEBUG_FRAMES) {
                    Log.d(TAG, "output format changed: ${codec.outputFormat}")
                }
            } while (outputBufferIndex >= 0)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            packet.release()
        }
    }

    private fun drainPackets() {
        while (true) {
            packets.poll()?.release() ?: break
        }
    }

    private fun releaseDecoder() {
        val codec = decoder ?: return
        try {
            codec.stop()
        } catch (_: Exception) {
        }
        try {
            codec.release()
        } catch (_: Exception) {
        }
        decoder = null
    }

    companion object {
        private const val TAG = "Receiver-Video"
        private const val DEBUG_FRAMES = false
        private const val MAX_BUFFERED_FRAMES = 6
        private const val MIME_TYPE = "video/avc"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val TIMEOUT_USEC = 1000L

        private fun decoderSupportsAdaptivePlayback(decoderInfo: MediaCodecInfo, mimeType: String): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                try {
                    if (decoderInfo.getCapabilitiesForType(mimeType)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
                    ) {
                        Log.i(TAG, "adaptive playback supported")
                        return true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            Log.w(TAG, "adaptive playback not supported")
            return false
        }
    }
}
