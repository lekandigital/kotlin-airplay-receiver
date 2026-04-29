package io.carmo.airplay.receiver.player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import io.carmo.airplay.receiver.model.NALPacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class VideoPlayer(
    private val surface: Surface,
    private val onLatencySample: (Long) -> Unit = {}
) : Thread("ReceiverVideoPlayer") {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val packets = ArrayBlockingQueue<NALPacket>(MAX_BUFFERED_FRAMES)
    private var decoder: MediaCodec? = null
    @Volatile private var isStopped = false

    fun addPacket(packet: NALPacket) {
        synchronized(packets) {
            if (packet.isCodecConfig) {
                drainPackets()
                if (!packets.offer(packet)) {
                    packet.release()
                }
                return
            }

            trimBacklog()
            if (!packets.offer(packet)) {
                packet.release()
                return
            }
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                videoFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, VIDEO_OPERATING_RATE)
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
            drainOutput(codec, 0L)

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
                codec.queueInputBuffer(inputBufferIndex, 0, packet.size, packet.presentationTimeUs, packet.codecFlags)
            } else if (DEBUG_FRAMES) {
                Log.d(TAG, "dequeueInputBuffer failed")
            }

            if (!packet.isCodecConfig && drainOutput(codec, TIMEOUT_USEC)) {
                onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            packet.release()
        }
    }

    private fun drainOutput(codec: MediaCodec, timeoutUsec: Long): Boolean {
        var pendingRenderIndex = -1

        outputLoop@ while (true) {
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUsec)
            when {
                outputBufferIndex >= 0 -> {
                    if (pendingRenderIndex >= 0) {
                        codec.releaseOutputBuffer(pendingRenderIndex, false)
                    }
                    pendingRenderIndex = outputBufferIndex
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (DEBUG_FRAMES) {
                        Log.d(TAG, "output format changed: ${codec.outputFormat}")
                    }
                }
                else -> {
                    break@outputLoop
                }
            }
        }

        if (pendingRenderIndex >= 0) {
            codec.releaseOutputBuffer(pendingRenderIndex, true)
            return true
        }
        return false
    }

    private fun trimBacklog() {
        while (packets.size >= MAX_QUEUED_FRAMES) {
            val packet = packets.peek() ?: return
            if (packet.isCodecConfig) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "preserving codec config; dropping incoming video frame")
                }
                return
            }
            packets.poll()?.release() ?: return
            if (DEBUG_FRAMES) {
                Log.d(TAG, "video queue full; dropped oldest frame")
            }
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
        private const val MAX_BUFFERED_FRAMES = 2
        private const val MAX_QUEUED_FRAMES = 1
        private const val MIME_TYPE = "video/avc"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_OPERATING_RATE = 60.0f
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
