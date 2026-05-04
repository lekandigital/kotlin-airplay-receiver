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
    private var shouldDropStartupFrames = true
    private var pendingStartupDropFrames = 0

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
            // Pre-drain any output that's already cooked. Use 0 timeout — we
            // poll-only here so we never block the input side of the pipeline.
            if (drainOutput(codec, 0L)) {
                signalFrameRendered()
            }

            // Reject oversized NAL up-front. We MUST NOT pass it to the decoder
            // in any form: queueing a zero-byte non-config buffer corrupts the
            // decoder state machine, and queueing it with the CODEC_CONFIG flag
            // would replace SPS/PPS with garbage. Just drop the packet and ask
            // for a fresh keyframe via the existing discontinuity machinery.
            val capacityProbeIndex = codec.dequeueInputBuffer(0L)
            if (capacityProbeIndex >= 0) {
                val probeBuffer = codec.getInputBuffer(capacityProbeIndex)
                if (probeBuffer != null && packet.size > probeBuffer.capacity()) {
                    Log.w(TAG, "NAL too large for decoder input buffer (size=${packet.size}, capacity=${probeBuffer.capacity()}); requesting keyframe")
                    // Send a harmless empty CODEC_CONFIG with our cached PTS to
                    // free the slot without poisoning state. Then mark a real
                    // discontinuity so we wait for the next IDR.
                    codec.queueInputBuffer(capacityProbeIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    markInputDiscontinuity(packet)
                    return
                }
                queueIntoBuffer(codec, capacityProbeIndex, packet)
            } else {
                // Back-pressure: decoder has no free input slot right now.
                // Wait for one — but DO NOT drop the packet or reset codec
                // config state on timeout. Doing so used to wedge the player
                // permanently if the surface stalled briefly at startup.
                val deadlineNs = System.nanoTime() + INPUT_WAIT_BUDGET_NS
                var inputIndex = -1
                while (!isStopped && System.nanoTime() < deadlineNs) {
                    inputIndex = codec.dequeueInputBuffer(INPUT_TIMEOUT_USEC)
                    if (inputIndex >= 0) break
                    // Drain output while we wait — this is what frees input slots.
                    if (drainOutput(codec, 0L)) {
                        signalFrameRendered()
                    }
                }
                if (inputIndex < 0) {
                    // Still no slot. For non-config frames we must drop and ask
                    // for a keyframe (skipping a P-frame breaks the GOP). For
                    // config we keep the cache — it'll be re-applied on the
                    // next keyframe by the state machine.
                    if (!packet.isCodecConfig) {
                        Log.w(TAG, "input back-pressure exceeded ${INPUT_WAIT_BUDGET_NS / 1_000_000}ms; dropping ${if (packet.isKeyFrame) "IDR" else "P"} frame")
                        isWaitingForKeyFrame = true
                    } else {
                        Log.w(TAG, "input back-pressure on codec config; will retry on next config push")
                    }
                    return
                }
                queueIntoBuffer(codec, inputIndex, packet)
            }

            if (!packet.isCodecConfig) {
                // After warm-up keep the post-queue wait short to minimise
                // end-to-end latency. During warm-up wait a bit longer so the
                // first IDR has a chance to surface a frame on this same tick.
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

    private fun queueIntoBuffer(codec: MediaCodec, inputBufferIndex: Int, packet: NALPacket) {
        val inputBuffer = codec.getInputBuffer(inputBufferIndex)
        if (inputBuffer == null) {
            // Shouldn't happen for a freshly-dequeued slot, but free it cleanly.
            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            return
        }

        inputBuffer.clear()
        packet.data.position(0)
        packet.data.limit(packet.size)
        inputBuffer.put(packet.data)

        if (packet.isCodecConfig) {
            shouldDropStartupFrames = true
            if (!hasLoggedConfigQueued) {
                Log.i(TAG, "queued codec config (SPS/PPS, size=${packet.size}) into MediaCodec")
                hasLoggedConfigQueued = true
            }
        } else if (packet.isKeyFrame) {
            if (shouldDropStartupFrames) {
                pendingStartupDropFrames = STARTUP_RENDER_DROP_FRAMES
                shouldDropStartupFrames = false
            }
            if (!hasLoggedKeyFrameQueued) {
                Log.i(TAG, "queued first IDR (size=${packet.size}, pts=${packet.presentationTimeUs}us)")
                hasLoggedKeyFrameQueued = true
            }
        }
        codec.queueInputBuffer(inputBufferIndex, 0, packet.size, packet.presentationTimeUs, packet.codecFlags)
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
            if (pendingStartupDropFrames > 0) {
                pendingStartupDropFrames--
                codec.releaseOutputBuffer(pendingRenderIndex, false)
                return false
            }
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

    private fun markInputDiscontinuity(packet: NALPacket) {
        if (packet.isCodecConfig) {
            hasCodecConfig = false
        }
        isWaitingForKeyFrame = true
        shouldDropStartupFrames = true
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
        // Keep the ingest queue small so we stay close to live. 8 frames at
        // 60fps = ~133ms worst-case, but in practice it sits near-empty once
        // the decoder is warm.
        private const val MAX_BUFFERED_FRAMES = 8
        private const val STARTUP_RENDER_DROP_FRAMES = 4
        private const val QUEUE_OFFER_TIMEOUT_MS = 5L
        private const val MIME_TYPE = "video/avc"
        private const val VIDEO_OPERATING_RATE = 60.0f
        private const val INPUT_TIMEOUT_USEC = 5_000L
        // Total time we'll spin waiting for an input buffer before declaring
        // back-pressure and asking for a fresh IDR. 80ms ≈ 5 frames at 60fps.
        private const val INPUT_WAIT_BUDGET_NS = 80_000_000L
        // Output drain wait: longer during warm-up so the very first IDR has a
        // chance to surface a frame in the same loop iteration; very short
        // afterwards to minimise added latency.
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
