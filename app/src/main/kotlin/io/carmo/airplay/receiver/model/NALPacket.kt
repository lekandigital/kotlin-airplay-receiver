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

    val isKeyFrame: Boolean
        get() = containsNalType(NAL_TYPE_IDR)

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

    private fun containsNalType(nalType: Int): Boolean {
        var index = 0
        while (index + 3 < size) {
            val startCodeLength = when {
                index + 3 < size &&
                    data.get(index) == START_CODE_BYTE &&
                    data.get(index + 1) == START_CODE_BYTE &&
                    data.get(index + 2) == START_CODE_ONE -> 3
                index + 4 < size &&
                    data.get(index) == START_CODE_BYTE &&
                    data.get(index + 1) == START_CODE_BYTE &&
                    data.get(index + 2) == START_CODE_BYTE &&
                    data.get(index + 3) == START_CODE_ONE -> 4
                else -> 0
            }

            if (startCodeLength > 0) {
                val nalHeaderIndex = index + startCodeLength
                if (nalHeaderIndex < size && (data.get(nalHeaderIndex).toInt() and NAL_TYPE_MASK) == nalType) {
                    return true
                }
                index = nalHeaderIndex + 1
            } else {
                index++
            }
        }
        return false
    }

    companion object {
        private const val NAL_TYPE_CODEC_CONFIG = 0
        private const val NAL_TYPE_MASK = 0x1F
        private const val NAL_TYPE_IDR = 5
        private const val START_CODE_BYTE: Byte = 0
        private const val START_CODE_ONE: Byte = 1
    }
}
