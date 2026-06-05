package io.carmo.airplay.receiver.pinn

class AdaptiveController(
    userBaselineProfile: String,
    aggressiveness: String
) {
    private var baselineProfile = normalizeProfile(userBaselineProfile)
    private var activeProfile = baselineProfile
    private val voteThreshold = when (aggressiveness) {
        "aggressive" -> 3
        "moderate" -> 5
        else -> 8
    }
    private var lastVote = 0
    private var consecutiveVotes = 0
    private var healthyReadings = 0
    private var coolReadings = 0

    fun updateBaseline(profile: String) {
        baselineProfile = normalizeProfile(profile)
        if (profileIndex(activeProfile) > profileIndex(baselineProfile)) {
            activeProfile = baselineProfile
        }
        resetVotes()
    }

    fun evaluate(prediction: PinnPrediction, observationMode: Boolean): AdaptiveDecision {
        if (observationMode) {
            return AdaptiveDecision(PinnActionType.HOLD, activeProfile, "observation mode")
        }

        val vote = when {
            prediction.buffer1s < PinnConfig.STALL_THRESHOLD_PACKETS -> -1
            prediction.thermalHeadroom < 0.2f -> -1
            prediction.qualityVote < -0.3f -> -1
            prediction.buffer3s > PinnConfig.HEALTHY_THRESHOLD_PACKETS &&
                prediction.thermalHeadroom > 0.8f &&
                prediction.qualityVote > 0.3f -> 1
            else -> 0
        }

        if (vote == 1) healthyReadings++ else healthyReadings = 0
        if (prediction.thermalHeadroom > 0.8f) coolReadings++ else coolReadings = 0
        trackVote(vote)

        if (vote < 0 && consecutiveVotes >= voteThreshold) {
            val next = downgrade(activeProfile)
            resetVotes()
            return if (next != activeProfile) {
                activeProfile = next
                AdaptiveDecision(PinnActionType.DOWNGRADE, activeProfile, "predicted stall or thermal pressure")
            } else {
                AdaptiveDecision(PinnActionType.HOLD, activeProfile, "already at lowest adaptive profile")
            }
        }

        if (vote > 0 && consecutiveVotes >= voteThreshold && healthyReadings >= voteThreshold && coolReadings >= voteThreshold) {
            val next = upgrade(activeProfile)
            resetVotes()
            return if (profileIndex(next) <= profileIndex(baselineProfile) && next != activeProfile) {
                activeProfile = next
                AdaptiveDecision(PinnActionType.UPGRADE, activeProfile, "buffer and thermal headroom recovered")
            } else {
                AdaptiveDecision(PinnActionType.HOLD, activeProfile, "baseline profile reached")
            }
        }

        return AdaptiveDecision(PinnActionType.HOLD, activeProfile, "holding")
    }

    fun fallback(audioUnderruns: Int, videoDrops: Int): AdaptiveDecision {
        return when {
            audioUnderruns > 3 -> AdaptiveDecision(PinnActionType.AUDIO_STABLE, "audio_stable", "audio underruns in recent window")
            videoDrops > 10 -> {
                val target = if (profileIndex(activeProfile) > profileIndex("low_latency")) {
                    "low_latency"
                } else {
                    activeProfile
                }
                AdaptiveDecision(PinnActionType.DOWNGRADE, target, "video queue pressure in recent window")
            }
            else -> AdaptiveDecision(PinnActionType.HOLD, activeProfile, "fallback holding")
        }
    }

    fun currentProfile(): String = activeProfile

    private fun trackVote(vote: Int) {
        if (vote == 0) {
            resetVotes()
            return
        }
        if (vote == lastVote) {
            consecutiveVotes++
        } else {
            lastVote = vote
            consecutiveVotes = 1
        }
    }

    private fun resetVotes() {
        lastVote = 0
        consecutiveVotes = 0
    }

    private fun downgrade(profile: String): String {
        val index = profileIndex(profile)
        return PROFILE_ORDER[(index - 1).coerceAtLeast(0)]
    }

    private fun upgrade(profile: String): String {
        val index = profileIndex(profile)
        val baselineIndex = profileIndex(baselineProfile)
        return PROFILE_ORDER[(index + 1).coerceAtMost(baselineIndex)]
    }

    private fun profileIndex(profile: String): Int {
        return PROFILE_ORDER.indexOf(normalizeProfile(profile)).takeIf { it >= 0 } ?: PROFILE_ORDER.indexOf("balanced")
    }

    private fun normalizeProfile(profile: String): String {
        return when (profile) {
            "best_quality", "auto" -> "best_quality"
            "balanced" -> "balanced"
            "low_latency" -> "low_latency"
            "compatibility", "audio_stable" -> "compatibility"
            else -> "balanced"
        }
    }

    companion object {
        private val PROFILE_ORDER = listOf(
            "compatibility",
            "low_latency",
            "balanced",
            "best_quality"
        )
    }
}
