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
    }
}
