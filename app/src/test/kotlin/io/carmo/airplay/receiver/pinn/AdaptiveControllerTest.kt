package io.carmo.airplay.receiver.pinn

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveControllerTest {
    @Test
    fun requiresConsecutiveVotesBeforeChangingProfile() {
        val controller = AdaptiveController("balanced", "moderate")
        val bad = PinnPrediction(1f, 1f, 1f, 1f, 0.9f, -0.8f)

        repeat(4) {
            assertEquals(PinnActionType.HOLD, controller.evaluate(bad, observationMode = false).action)
        }
        val decision = controller.evaluate(bad, observationMode = false)

        assertEquals(PinnActionType.DOWNGRADE, decision.action)
        assertEquals("low_latency", decision.targetProfile)
    }

    @Test
    fun neverUpgradesBeyondUserBaseline() {
        val controller = AdaptiveController("low_latency", "moderate")
        val good = PinnPrediction(20f, 20f, 20f, 20f, 0.95f, 0.8f)

        repeat(5) {
            controller.evaluate(good, observationMode = false)
        }
        val decision = controller.evaluate(good, observationMode = false)

        assertEquals(PinnActionType.HOLD, decision.action)
        assertEquals("low_latency", decision.targetProfile)
    }
}
