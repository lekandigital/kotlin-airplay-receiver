package io.carmo.airplay.receiver.model

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

    @Synchronized
    fun release() {
        if (pointer != 0L) {
            NativeMemory.free(pointer)
            pointer = 0L
        }
    }
}
