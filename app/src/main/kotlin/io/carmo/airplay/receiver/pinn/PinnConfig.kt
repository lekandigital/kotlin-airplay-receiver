package io.carmo.airplay.receiver.pinn

object PinnConfig {
    const val INPUT_DIM = 16
    const val HIDDEN_1 = 32
    const val HIDDEN_2 = 16
    const val OUTPUT_DIM = 6
    const val PARAMETER_COUNT = INPUT_DIM * HIDDEN_1 + HIDDEN_1 +
        HIDDEN_1 * HIDDEN_2 + HIDDEN_2 +
        HIDDEN_2 * OUTPUT_DIM + OUTPUT_DIM

    const val MAX_BUFFER_PACKETS = 96f
    const val STALL_THRESHOLD_PACKETS = 2f
    const val HEALTHY_THRESHOLD_PACKETS = 12f
    const val OBSERVATION_MODE_MS = 60_000L
    const val SAMPLE_INTERVAL_MS = 500L
    const val TRAIN_INTERVAL_MS = 30_000L
    const val MAX_SAMPLES = 600
    const val BATCH_SIZE = 32
    const val LEARNING_RATE = 0.001f
    const val MOMENTUM = 0.9f
    const val PHYSICS_WEIGHT = 0.1f
    const val BOUNDARY_WEIGHT = 1.0f
    const val HIGH_LOSS_LIMIT = 1.5f
    const val HIGH_LOSS_DISABLE_STEPS = 100
    const val WEIGHTS_VERSION = 1
    const val WEIGHTS_FILE_NAME = "pinn_weights.bin"
}

data class PinnPrediction(
    val buffer05s: Float,
    val buffer1s: Float,
    val buffer2s: Float,
    val buffer3s: Float,
    val thermalHeadroom: Float,
    val qualityVote: Float
) {
    val buffers: FloatArray get() = floatArrayOf(buffer05s, buffer1s, buffer2s, buffer3s)
}

data class PinnLossBreakdown(
    val data: Float = 0f,
    val physics: Float = 0f,
    val boundary: Float = 0f
) {
    val total: Float get() = data + PinnConfig.PHYSICS_WEIGHT * physics + PinnConfig.BOUNDARY_WEIGHT * boundary
}

data class PinnDiagnosticsState(
    val enabled: Boolean = false,
    val status: String = "disabled",
    val trainingIterations: Int = 0,
    val prediction: PinnPrediction? = null,
    val downgrades: Int = 0,
    val upgrades: Int = 0,
    val estimatedBandwidthKbps: Float = 0f,
    val estimatedThermalConductivity: Float = 0f,
    val loss: PinnLossBreakdown = PinnLossBreakdown(),
    val lastAction: String = "",
    val autoDisabled: Boolean = false
)

enum class PinnActionType {
    NONE,
    DOWNGRADE,
    UPGRADE,
    HOLD,
    AUDIO_STABLE
}

data class AdaptiveDecision(
    val action: PinnActionType,
    val targetProfile: String?,
    val reason: String
)
