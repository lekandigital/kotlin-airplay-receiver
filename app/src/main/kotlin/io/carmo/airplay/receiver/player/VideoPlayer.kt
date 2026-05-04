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
    @Volatile private var hasRenderedFirstFrame = false

    // Diagnostic flags — log critical lifecycle events exactly once per player.
    private var hasLoggedConfigQueued = false
    private var hasLoggedKeyFrameQueued = false
    private var hasLoggedFirstRender = false
    private var hasLoggedDecoderMissing = false

    /** True once the decoder has produced and rendered at least one frame. */
    fun hasRenderedFirstFrame(): Boolean = hasRenderedFirstFrame

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
            // KEY_LOW_LATENCY (API 30+) tells the decoder to skip its output
            // reorder buffer entirely. AirPlay mirroring sends in display order
            // with no B-frames, so this is safe and shaves several frames of
            // pipeline latency once the codec is past its initial fill.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                videoFormat.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }

            codec.configure(videoFormat, surface, null, 0)
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            codec.start()
            decoder = codec
        } catch (e: Exception) {
            Log.e(TAG, "failed to initialise decoder for ${width}x${height}", e)
            releaseDecoder()
        }
    }

    private fun decode(packet: NALPacket) {
        val codec = decoder
        if (codec == null) {
            if (!hasLoggedDecoderMissing) {
                Log.w(TAG, "decoder unavailable; dropping packet (size=${packet.size}, config=${packet.isCodecConfig}, key=${packet.isKeyFrame})")
                hasLoggedDecoderMissing = true
            }
            packet.release()
            return
        }

        try {
            // Drain any output that's already cooked. Use 0 timeout — we
            // poll-only here so we never block the input side.
            if (drainOutput(codec, 0L)) {
                signalFrameRendered()
            }

            // Wait briefly for an input slot. If none is free we DO NOT trash
            // codec config state — we just drop this one frame and ask for a
            // fresh keyframe. Resetting hasCodecConfig on transient back-pressure
            // (the previous behavior) used to wedge the decoder permanently.
            val inputBufferIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_USEC)
            if (inputBufferIndex < 0) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "dequeueInputBuffer timed out")
                }
                if (!packet.isCodecConfig) {
                    isWaitingForKeyFrame = true
                }
                return
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            if (inputBuffer == null || packet.size > inputBuffer.capacity()) {
                if (DEBUG_FRAMES) {
                    Log.d(TAG, "dropping oversized NAL: size=${packet.size}, capacity=${inputBuffer?.capacity()}")
                }
                // Free the slot WITHOUT a CODEC_CONFIG flag (we don't have config
                // bytes to give it). Empty submission is the cleanest way to
                // release the input buffer back to the codec.
                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, 0)
                if (!packet.isCodecConfig) {
                    isWaitingForKeyFrame = true
                }
                return
            }

            inputBuffer.clear()
            packet.data.position(0)
            packet.data.limit(packet.size)
            inputBuffer.put(packet.data)
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                packet.size,
                packet.presentationTimeUs,
                packet.codecFlags
            )

            if (packet.isCodecConfig) {
                if (!hasLoggedConfigQueued) {
                    Log.i(TAG, "queued codec config (SPS/PPS, size=${packet.size}) into MediaCodec")
                    hasLoggedConfigQueued = true
                }
            } else {
                if (packet.isKeyFrame && !hasLoggedKeyFrameQueued) {
                    Log.i(TAG, "queued first IDR (size=${packet.size}, pts=${packet.presentationTimeUs}us)")
                    hasLoggedKeyFrameQueued = true
                }
                // Drain output. Use a longer wait until we've rendered something
                // (helps surface the very first frame promptly), then drop to 0
                // for steady-state low latency.
                val outputWait = if (hasRenderedFirstFrame) STEADY_OUTPUT_TIMEOUT_USEC else WARMUP_OUTPUT_TIMEOUT_USEC
                if (drainOutput(codec, outputWait)) {
                    signalFrameRendered()
                    onLatencySample(SystemClock.elapsedRealtime() - packet.receivedAtMs)
                }
            }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "decoder threw — releasing", e)
            releaseDecoder()
        } catch (e: Exception) {
            Log.e(TAG, "decode error", e)
        } finally {
            packet.release()
        }
    }

    private fun signalFrameRendered() {
        if (!hasRenderedFirstFrame) {
            hasRenderedFirstFrame = true
        }
        if (!hasLoggedFirstRender) {
            Log.i(TAG, "first frame rendered to surface")
            hasLoggedFirstRender = true
        }
        onFrameRendered()
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
        if (offerPacket(packet)) {
            return
        }

        if (packet.isKeyFrame) {
            drainPendingVideoFrames()
            isWaitingForKeyFrame = false
            if (!offerPacket(packet)) {
                packet.release()
            }
            return
        }

        isWaitingForKeyFrame = true
        packet.release()
    }

    private fun offerPacket(packet: NALPacket): Boolean {
        return try {
            packets.offer(packet, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
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
        // Small queue keeps us close to live. The 0.2.12 tip used 2; we allow
        // a little more headroom for momentary jitter.
        private const val MAX_BUFFERED_FRAMES = 4
        private const val QUEUE_OFFER_TIMEOUT_MS = 5L
        private const val MIME_TYPE = "video/avc"
        private const val VIDEO_OPERATING_RATE = 60.0f
        private const val INPUT_TIMEOUT_USEC = 10_000L
        // Output drain wait: a bit longer until the first frame surfaces (so
        // warm-up doesn't sit idle), then 0 for steady-state low latency.
        private const val WARMUP_OUTPUT_TIMEOUT_USEC = 8_000L
        private const val STEADY_OUTPUT_TIMEOUT_USEC = 0L

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
