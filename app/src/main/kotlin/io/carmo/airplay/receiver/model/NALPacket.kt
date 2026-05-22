package io.carmo.airplay.receiver.model

import android.media.MediaCodec
import java.nio.ByteBuffer

class NALPacket(
    val data: ByteBuffer,
    val size: Int,
    nativePointer: Long,
    val nalType: Int,
    val pts: Long,
    val dts: Long,
    val receivedAtMs: Long
) {
    private var pointer: Long = nativePointer

    val isCodecConfig: Boolean
        get() = nalType == NAL_TYPE_CODEC_CONFIG

    val presentationTimeUs: Long
        get() = if (pts > 0L) pts else 0L

    val codecFlags: Int
        get() = if (isCodecConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0

    @Synchronized
    fun release() {
        if (pointer != 0L) {
            NativeMemory.free(pointer)
            pointer = 0L
        }
    }

    companion object {
        private const val NAL_TYPE_CODEC_CONFIG = 0

        /**
         * Creates a synthetic codec-config NAL packet from a Java byte array.
         * The resulting packet has no native pointer and is safe to release without
         * touching native memory. Used to replay cached SPS/PPS into a freshly
         * created [io.carmo.airplay.receiver.player.VideoPlayer] when the original
         * codec config was received before the player existed (e.g. surface was
         * not yet valid, or it was torn down between sessions).
         */
        fun forCodecConfig(bytes: ByteArray, receivedAtMs: Long): NALPacket {
            val copy = bytes.copyOf()
            val buffer = ByteBuffer.wrap(copy)
            buffer.position(0)
            buffer.limit(copy.size)
            return NALPacket(
                data = buffer,
                size = copy.size,
                nativePointer = 0L,
                nalType = NAL_TYPE_CODEC_CONFIG,
                pts = 0L,
                dts = 0L,
                receivedAtMs = receivedAtMs
            )
        }
    }
}
