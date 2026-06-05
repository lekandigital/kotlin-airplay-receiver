package io.carmo.airplay.receiver.pinn

import kotlin.math.abs

data class BufferPhysicsSample(
    val bufferStart: Float,
    val bufferEnd: Float,
    val deltaSeconds: Float,
    val receiveRate: Float,
    val drainRate: Float,
    val lossRate: Float,
    val averageReceiveRate: Float,
    val averageWaitSeconds: Float,
    val averageBuffer: Float
)

data class ThermalPhysicsSample(
    val temperatureStart: Float,
    val temperatureEnd: Float,
    val deltaSeconds: Float,
    val decodePower: Float,
    val ambientTemperature: Float,
    val thermalCapacitance: Float,
    val thermalResistance: Float
)

object PinnLoss {
    fun physicsResidual(
        buffer: BufferPhysicsSample,
        thermal: ThermalPhysicsSample
    ): Float {
        return bufferConservationResidual(buffer) +
            littleLawResidual(buffer) +
            thermalResidual(thermal)
    }

    fun bufferConservationResidual(sample: BufferPhysicsSample): Float {
        val dBdt = (sample.bufferEnd - sample.bufferStart) / sample.deltaSeconds.coerceAtLeast(0.001f)
        val expected = sample.receiveRate - sample.drainRate - sample.lossRate
        val residual = dBdt - expected
        return residual * residual
    }

    fun littleLawResidual(sample: BufferPhysicsSample): Float {
        val residual = sample.averageBuffer - sample.averageReceiveRate * sample.averageWaitSeconds
        return residual * residual
    }

    fun thermalResidual(sample: ThermalPhysicsSample): Float {
        val dTdt = (sample.temperatureEnd - sample.temperatureStart) / sample.deltaSeconds.coerceAtLeast(0.001f)
        val residual = sample.thermalCapacitance * dTdt -
            sample.decodePower +
            (sample.temperatureStart - sample.ambientTemperature) / sample.thermalResistance.coerceAtLeast(0.001f)
        return residual * residual
    }

    fun boundaryLoss(prediction: PinnPrediction): Float {
        var loss = 0f
        prediction.buffers.forEach { buffer ->
            if (buffer < 0f) loss += abs(buffer)
        }
        if (prediction.thermalHeadroom !in 0f..1f) {
            loss += abs(prediction.thermalHeadroom.coerceIn(0f, 1f) - prediction.thermalHeadroom)
        }
        return loss
    }
}
