package io.carmo.airplay.receiver.model

object NativeMemory {
    external fun free(pointer: Long)

    init {
        System.loadLibrary("raop_server")
    }
}
