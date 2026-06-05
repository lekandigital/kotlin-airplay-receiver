package io.carmo.airplay.receiver.pinn

import org.junit.Assert.assertEquals
import org.junit.Test

class PinnLossTest {
    @Test
    fun physicsResidualIsZeroWhenEquationsAreSatisfied() {
        val buffer = BufferPhysicsSample(
            bufferStart = 10f,
            bufferEnd = 12f,
            deltaSeconds = 1f,
            receiveRate = 5f,
            drainRate = 2f,
            lossRate = 1f,
            averageReceiveRate = 4f,
            averageWaitSeconds = 2f,
            averageBuffer = 8f
        )
        val thermal = ThermalPhysicsSample(
            temperatureStart = 40f,
            temperatureEnd = 42f,
            deltaSeconds = 1f,
            decodePower = 25f,
            ambientTemperature = 30f,
            thermalCapacitance = 10f,
            thermalResistance = 2f
        )

        assertEquals(0f, PinnLoss.physicsResidual(buffer, thermal), 0.0001f)
    }
}
