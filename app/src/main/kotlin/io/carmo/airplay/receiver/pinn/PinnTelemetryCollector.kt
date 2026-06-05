package io.carmo.airplay.receiver.pinn

import android.content.Context
import android.os.Build
import android.util.Log
import io.carmo.airplay.receiver.ReceiverPreferences
import io.carmo.airplay.receiver.ReceiverRuntime
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

class PinnTelemetryCollector(
    private val context: Context,
    private val runtime: ReceiverRuntime,
    private val onDecision: (AdaptiveDecision) -> Unit
) {
    private val appContext = context.applicationContext
    private val network = PinnNetwork.loadOrCreate(weightsFile(appContext))
    private val controller = AdaptiveController(
        ReceiverPreferences.qualityProfile(appContext),
        ReceiverPreferences.pinnAggressiveness(appContext)
    )
    private val trafficSamples = ArrayDeque<Int>()
    private val latencySamples = ArrayDeque<Long>()
    private val windows = ArrayDeque<TelemetryWindow>()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ReceiverPINN").apply { isDaemon = true }
    }
    private val trafficListener: (Int) -> Unit = { bytes -> addTraffic(bytes) }
    private val latencyListener: (Long) -> Unit = { latencyMs -> addLatency(latencyMs) }

    @Volatile private var running = false
    @Volatile private var diagnosticsState = PinnDiagnosticsState(enabled = true, status = "observation")
    private var startedAtMs = 0L
    private var lastTrainAtMs = 0L
    private var lastFrames = 0
    private var lastUnderruns = 0
    private var lastDecoderRestarts = 0
    private var trainingIterations = 0
    private var highLossSteps = 0
    private var downgrades = 0
    private var upgrades = 0
    private var lastAction = ""
    private var autoDisabled = false
    private var estimatedBandwidthKbps = 0f
    private var lastLoss = PinnLossBreakdown()

    fun start() {
        if (running) return
        running = true
        startedAtMs = System.currentTimeMillis()
        runtime.addTrafficListener(trafficListener)
        runtime.addLatencyListener(latencyListener)
        executor.scheduleAtFixedRate(
            ::sampleSafely,
            0L,
            PinnConfig.SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        if (!running) return
        running = false
        runtime.removeTrafficListener(trafficListener)
        runtime.removeLatencyListener(latencyListener)
        runCatching { network.save(weightsFile(appContext)) }
        executor.shutdownNow()
    }

    fun diagnostics(): PinnDiagnosticsState = diagnosticsState

    private fun addTraffic(bytes: Int) {
        synchronized(trafficSamples) {
            trafficSamples.addLast(bytes)
            while (trafficSamples.size > 10) trafficSamples.removeFirst()
        }
    }

    private fun addLatency(latencyMs: Long) {
        synchronized(latencySamples) {
            latencySamples.addLast(latencyMs)
            while (latencySamples.size > 10) latencySamples.removeFirst()
        }
    }

    private fun sampleSafely() {
        try {
            sample()
        } catch (e: Throwable) {
            Log.w(TAG, "PINN sample failed", e)
        }
    }

    private fun sample() {
        if (!running) return
        val now = System.currentTimeMillis()
        val stats = runtime.sessionStatsForPinn()
        val videoQueue = runtime.videoQueueSizeForPinn()
        val audioQueue = runtime.audioQueueSizeForPinn()
        val frameDelta = (stats.videoFramesRendered - lastFrames).coerceAtLeast(0)
        val underrunDelta = (stats.audioUnderruns - lastUnderruns).coerceAtLeast(0)
        val restartDelta = (stats.decoderRestarts - lastDecoderRestarts).coerceAtLeast(0)
        lastFrames = stats.videoFramesRendered
        lastUnderruns = stats.audioUnderruns
        lastDecoderRestarts = stats.decoderRestarts

        val traffic = synchronized(trafficSamples) { trafficSamples.toList() }
        val latency = synchronized(latencySamples) { latencySamples.toList() }
        val features = buildFeatures(
            traffic = traffic,
            latency = latency,
            videoQueue = videoQueue,
            audioQueue = audioQueue,
            frameDelta = frameDelta,
            lossDelta = underrunDelta + restartDelta,
            elapsedMs = now - startedAtMs
        )
        val window = TelemetryWindow(
            timestampMs = now,
            features = features,
            videoQueue = videoQueue,
            audioQueue = audioQueue,
            frames = stats.videoFramesRendered,
            audioUnderruns = stats.audioUnderruns
        )
        windows.addLast(window)
        while (windows.size > PinnConfig.MAX_SAMPLES) windows.removeFirst()

        val observationMode = now - startedAtMs < PinnConfig.OBSERVATION_MODE_MS
        val prediction = if (autoDisabled) null else network.forward(features)
        val status = when {
            autoDisabled -> "disabled for this session"
            observationMode -> "observation"
            else -> "active"
        }
        if (prediction != null) {
            val decision = controller.evaluate(prediction, observationMode)
            applyDecision(decision)
        }
        if (now - lastTrainAtMs >= PinnConfig.TRAIN_INTERVAL_MS && !observationMode && !autoDisabled) {
            trainBatch()
            lastTrainAtMs = now
        }
        diagnosticsState = PinnDiagnosticsState(
            enabled = true,
            status = status,
            trainingIterations = trainingIterations,
            prediction = prediction,
            downgrades = downgrades,
            upgrades = upgrades,
            estimatedBandwidthKbps = estimatedBandwidthKbps,
            estimatedThermalConductivity = estimatedThermalConductivity(),
            loss = lastLoss,
            lastAction = lastAction,
            autoDisabled = autoDisabled
        )
    }

    private fun buildFeatures(
        traffic: List<Int>,
        latency: List<Long>,
        videoQueue: Int,
        audioQueue: Int,
        frameDelta: Int,
        lossDelta: Int,
        elapsedMs: Long
    ): FloatArray {
        val throughputKbps = traffic.map { it * 8f * 2f / 1000f }
        val throughputMean = throughputKbps.mean()
        val throughputVariance = throughputKbps.variance(throughputMean)
        estimatedBandwidthKbps = throughputMean
        val latencyMean = latency.map { it.toFloat() }.mean()
        val latencyVariance = latency.map { it.toFloat() }.variance(latencyMean)
        val quality = ReceiverPreferences.qualityProfile(appContext)
        val features = FloatArray(PinnConfig.INPUT_DIM)
        features[0] = normalize(throughputMean, 50_000f)
        features[1] = normalize(sqrt(throughputVariance), 20_000f)
        features[2] = normalize(latencyMean, 500f)
        features[3] = normalize(sqrt(latencyVariance), 250f)
        features[4] = normalize(videoQueue.toFloat(), PinnConfig.MAX_BUFFER_PACKETS)
        features[5] = normalize(audioQueue.toFloat(), 128f)
        features[6] = normalize(frameDelta.toFloat(), 30f)
        features[7] = normalize(lossDelta.toFloat(), 20f)
        features[8] = normalize(thermalProxy(), 100f)
        features[9] = normalize(elapsedMs.toFloat(), 300_000f)
        features[10] = if (quality == ReceiverPreferences.QUALITY_AUTO) 1f else 0f
        features[11] = if (quality == ReceiverPreferences.QUALITY_LOW_LATENCY) 1f else 0f
        features[12] = if (quality == ReceiverPreferences.QUALITY_BALANCED) 1f else 0f
        features[13] = if (quality == ReceiverPreferences.QUALITY_BEST) 1f else 0f
        features[14] = if (quality == ReceiverPreferences.QUALITY_COMPATIBILITY) 1f else 0f
        features[15] = if (quality == ReceiverPreferences.QUALITY_AUDIO_STABLE) 1f else 0f
        return features
    }

    private fun trainBatch() {
        if (windows.size < 8) return
        val items = windows.toList().takeLast(PinnConfig.BATCH_SIZE)
        var dataLoss = 0f
        items.forEach { window ->
            val target = targetFor(window)
            val loss = network.train(window.features, target)
            dataLoss += loss.data
        }
        val averageDataLoss = dataLoss / items.size
        val physicsLoss = physicsLossFor(items.last())
        lastLoss = PinnLossBreakdown(data = averageDataLoss, physics = physicsLoss, boundary = lastLoss.boundary)
        trainingIterations++
        if (averageDataLoss > PinnConfig.HIGH_LOSS_LIMIT) {
            highLossSteps++
            if (highLossSteps >= PinnConfig.HIGH_LOSS_DISABLE_STEPS) {
                autoDisabled = true
                lastAction = "model disabled after persistent high loss"
            }
        } else {
            highLossSteps = 0
        }
    }

    private fun targetFor(window: TelemetryWindow): PinnTrainingTarget {
        val current = windows.lastOrNull()
        val queue = ((current?.videoQueue ?: window.videoQueue) + (current?.audioQueue ?: window.audioQueue)) / 2f
        val buffer = queue.coerceIn(0f, PinnConfig.MAX_BUFFER_PACKETS)
        return PinnTrainingTarget(
            buffers = floatArrayOf(buffer, buffer, buffer, buffer),
            thermalHeadroom = (1f - normalize(thermalProxy(), 100f)).coerceIn(0f, 1f),
            qualityVote = 0f
        )
    }

    private fun physicsLossFor(window: TelemetryWindow): Float {
        val buffer = BufferPhysicsSample(
            bufferStart = window.videoQueue.toFloat(),
            bufferEnd = (windows.lastOrNull()?.videoQueue ?: window.videoQueue).toFloat(),
            deltaSeconds = PinnConfig.SAMPLE_INTERVAL_MS / 1000f,
            receiveRate = estimatedBandwidthKbps / 8f,
            drainRate = window.frames.toFloat().coerceAtLeast(1f),
            lossRate = window.audioUnderruns.toFloat(),
            averageReceiveRate = estimatedBandwidthKbps / 8f,
            averageWaitSeconds = 0.5f,
            averageBuffer = window.videoQueue.toFloat()
        )
        val thermal = ThermalPhysicsSample(
            temperatureStart = thermalProxy(),
            temperatureEnd = thermalProxy(),
            deltaSeconds = PinnConfig.SAMPLE_INTERVAL_MS / 1000f,
            decodePower = runtime.currentFrameRateForPinn().coerceAtLeast(1f),
            ambientTemperature = 30f,
            thermalCapacitance = 10f,
            thermalResistance = 2f
        )
        return PinnLoss.physicsResidual(buffer, thermal)
    }

    private fun applyDecision(decision: AdaptiveDecision) {
        if (decision.action == PinnActionType.HOLD || decision.targetProfile == null) {
            return
        }
        when (decision.action) {
            PinnActionType.DOWNGRADE -> downgrades++
            PinnActionType.UPGRADE -> upgrades++
            else -> Unit
        }
        lastAction = "${decision.action.name.lowercase(Locale.US)} to ${decision.targetProfile}: ${decision.reason}"
        onDecision(decision)
    }

    private fun thermalProxy(): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val manager = appContext.getSystemService(Context.HARDWARE_PROPERTIES_SERVICE)
            val temps = runCatching {
                val method = manager?.javaClass?.getMethod("getDeviceTemperatures", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
                @Suppress("UNCHECKED_CAST")
                method?.invoke(manager, 0, 0) as? FloatArray
            }.getOrNull()
            val temp = temps?.firstOrNull { it > 0f }
            if (temp != null) return temp
        }
        return 35f + runtime.currentFrameRateForPinn().coerceAtLeast(0f) * 0.05f
    }

    private fun estimatedThermalConductivity(): Float {
        return (1f / 2f) * (1f - normalize(thermalProxy(), 120f))
    }

    private fun normalize(value: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return (value / max).coerceIn(0f, 1f)
    }

    private fun List<Float>.mean(): Float {
        return if (isEmpty()) 0f else sum() / size
    }

    private fun List<Float>.variance(mean: Float): Float {
        if (isEmpty()) return 0f
        var sum = 0f
        forEach { value ->
            val error = value - mean
            sum += error * error
        }
        return sum / size
    }

    private data class TelemetryWindow(
        val timestampMs: Long,
        val features: FloatArray,
        val videoQueue: Int,
        val audioQueue: Int,
        val frames: Int,
        val audioUnderruns: Int
    )

    companion object {
        private const val TAG = "Receiver-PINN"

        fun weightsFile(context: Context): File {
            return File(context.filesDir, PinnConfig.WEIGHTS_FILE_NAME)
        }

        fun resetWeights(context: Context): Boolean {
            return weightsFile(context.applicationContext).delete()
        }
    }
}
