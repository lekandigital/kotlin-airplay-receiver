package io.carmo.airplay.receiver.model

import java.nio.ByteBuffer

class PCMPacket(
    val data: ByteBuffer,
    val size: Int,
    nativePointer: Long,
    val pts: Long
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
