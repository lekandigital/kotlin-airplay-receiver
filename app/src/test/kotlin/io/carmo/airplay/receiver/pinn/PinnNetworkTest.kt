package io.carmo.airplay.receiver.pinn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnNetworkTest {
    @Test
    fun forwardProducesBoundedOutputs() {
        val network = PinnNetwork.create(seed = 42L)
        val input = FloatArray(PinnConfig.INPUT_DIM) { 0.5f }

        val prediction = network.forward(input)

        assertEquals(PinnConfig.PARAMETER_COUNT, network.parameterCount())
        assertTrue(prediction.buffer05s >= 0f)
        assertTrue(prediction.buffer1s >= 0f)
        assertTrue(prediction.buffer2s >= 0f)
        assertTrue(prediction.buffer3s >= 0f)
        assertTrue(prediction.thermalHeadroom in 0f..1f)
        assertTrue(prediction.qualityVote in -1f..1f)
    }
}
