package io.carmo.airplay.receiver.model

data class NALPacket(
    val nalData: ByteArray,
    val nalType: Int,
    val pts: Long,
    val dts: Long
)
