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
    private val width: Int,
    private val height: Int,
    private val onLatencySample: (Long) -> Unit = {},
    private val onFrameRendered: () -> Unit = {}
) : Thread("ReceiverVideoPlayer") {

    private val bufferInfo = MediaCodec.BufferInfo()
    private val packets = ArrayBlockingQueue<NALPacket>(MAX_BUFFERED_FRAMES)
    private var decoder: MediaCodec? = null
    @Volatile private var isStopped = false
    @Volatile private var hasCodecConfig = false
    @Volatile private var isWaitingForKeyFrame = true

    fun addPacket(packet: NALPacket) {
        synchronized(packets) {
            if (packet.isCodecConfig) {
                drainPackets()
                hasCodecConfig = true
                isWaitingForKeyFrame = true
                if (!packets.offer(packet)) {
                    packet.release()
                }
                return
            }

            if (!hasCodecConfig) {
                packet.release()
                return
            }
            if (isWaitingForKeyFrame) {
                if (!packet.isKeyFrame) {
                    packet.release()
                    return
                }
                isWaitingForKeyFrame = false
            }
            enqueueVideoPacket(packet)
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
            val videoFormat = MediaFormat.createVideoFormat(MIME_TYPE, width, height)
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)

            val decoderInfo = codec.codecInfo
            Log.i(TAG, "decoder selected = ${decoderInfo.name}, size = ${width}x${height}")

            if (decoderSupportsAdaptivePlayback(decoderInfo, MIME_TYPE)) {
                videoFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, width)
                videoFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, height)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                videoFormat.setInteger(MediaFormat.KEY_PRIORITY, 0)
                videoFormat.setFloat(MediaFormat.KEY_OPERATING_RATE, VIDEO_OPERATING_RATE)
            }

            codec.configure(videoFormat, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.start()
            decoder = codec
        } catch (e: Exception) {
            releaseDecoder()
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
            if (drainOutput(codec, 0L)) {
                onFrameRendered()
            }

            var queuedInput = false
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_USEC)
            if (inputBufferIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                if (inputBuffer == null || packet.size > inputBuffer.capacity()) {
                    if (DEBUG_FRAMES) {
                        Log.d(TAG, "dropping oversized NAL: ${packet.size}")
                    }
                    markInputDiscontinuity(packet)
                    codec.queueInputBuffer(inputBufferIndex, 0, 0, packet.pts, 0)
                    return
                }

                inputBuffer.clear()
                packet.data.position(0)
                packet.data.limit(packet.size)
                inputBuffer.put(packet.data)
                codec.queueInputBuffer(inputBufferIndex, 0, packet.size, packet.presentationTimeUs, packet.codecFlags)
                queuedInput = true
            } else {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "dequeueInputBuffer failed")
                }
                markInputDiscontinuity(packet)
            }

            if (queuedInput && !packet.isCodecConfig && drainOutput(codec, OUTPUT_TIMEOUT_USEC)) {
                onFrameRendered()
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

    private fun enqueueVideoPacket(packet: NALPacket) {
        if (packets.offer(packet)) {
            return
        }
        drainPendingVideoFrames()
        isWaitingForKeyFrame = true
        if (packet.isKeyFrame) {
            isWaitingForKeyFrame = false
            if (!packets.offer(packet)) {
                packet.release()
            }
        } else {
            packet.release()
        }
    }

    private fun drainPendingVideoFrames() {
        val retainedConfig = ArrayList<NALPacket>(MAX_BUFFERED_FRAMES)
        while (true) {
            val queuedPacket = packets.poll() ?: break
            if (queuedPacket.isCodecConfig) {
                retainedConfig.add(queuedPacket)
            } else {
                queuedPacket.release()
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "dropped queued video frame")
                }
            }
        }
        retainedConfig.forEach { packets.offer(it) }
    }

    private fun markInputDiscontinuity(packet: NALPacket) {
        if (packet.isCodecConfig) {
            hasCodecConfig = false
        }
        isWaitingForKeyFrame = true
        synchronized(packets) {
            drainPendingVideoFrames()
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
        private const val MAX_BUFFERED_FRAMES = 24
        private const val MIME_TYPE = "video/avc"
        private const val VIDEO_OPERATING_RATE = 60.0f
        private const val INPUT_TIMEOUT_USEC = 10_000L
        private const val OUTPUT_TIMEOUT_USEC = 1_000L

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
