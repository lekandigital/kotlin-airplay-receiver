package io.carmo.airplay.receiver.pinn

import java.util.Locale

object PinnDiagnostics {
    fun format(state: PinnDiagnosticsState): String {
        return buildString {
            appendLine("PINN adaptive streaming:")
            appendLine("  Enabled: ${state.enabled}")
            appendLine("  Model status: ${state.status}")
            appendLine("  Training iterations: ${state.trainingIterations}")
            appendLine("  Quality adjustments: ${state.downgrades} downgrades, ${state.upgrades} upgrades")
            appendLine("  Last action: ${state.lastAction.ifBlank { "none" }}")
            appendLine("  Auto-disabled this session: ${state.autoDisabled}")
            state.prediction?.let { prediction ->
                appendLine(
                    "  Buffer forecast: %.1f / %.1f / %.1f / %.1f packets".format(
                        Locale.US,
                        prediction.buffer05s,
                        prediction.buffer1s,
                        prediction.buffer2s,
                        prediction.buffer3s
                    )
                )
                appendLine("  Thermal headroom: %.2f".format(Locale.US, prediction.thermalHeadroom))
                appendLine("  Quality vote: %.2f".format(Locale.US, prediction.qualityVote))
            }
            appendLine("  Estimated bandwidth: %.0f kbps".format(Locale.US, state.estimatedBandwidthKbps))
            appendLine("  Estimated thermal conductivity: %.3f".format(Locale.US, state.estimatedThermalConductivity))
            appendLine("  Loss data: %.4f".format(Locale.US, state.loss.data))
            appendLine("  Loss physics: %.4f".format(Locale.US, state.loss.physics))
            appendLine("  Loss boundary: %.4f".format(Locale.US, state.loss.boundary))
        }
    }
}
