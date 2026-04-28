package io.carmo.airplay.receiver.model

data class PCMPacket(
    val data: ShortArray,
    val pts: Long
)
