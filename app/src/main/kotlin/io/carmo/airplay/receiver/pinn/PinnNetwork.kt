package io.carmo.airplay.receiver.pinn

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.Random
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.math.tanh

data class PinnTrainingTarget(
    val buffers: FloatArray,
    val thermalHeadroom: Float,
    val qualityVote: Float = 0f
)

class PinnNetwork private constructor(
    private val params: FloatArray
) {
    private val velocity = FloatArray(params.size)

    fun parameterCount(): Int = params.size

    fun parametersCopy(): FloatArray = params.copyOf()

    fun forward(input: FloatArray): PinnPrediction {
        require(input.size == PinnConfig.INPUT_DIM) { "Expected ${PinnConfig.INPUT_DIM} inputs" }
        val h1 = FloatArray(PinnConfig.HIDDEN_1)
        val h2 = FloatArray(PinnConfig.HIDDEN_2)
        val raw = FloatArray(PinnConfig.OUTPUT_DIM)
        forwardRaw(input, h1, h2, raw)
        return activate(raw)
    }

    fun train(input: FloatArray, target: PinnTrainingTarget): PinnLossBreakdown {
        require(target.buffers.size >= 4) { "Expected four buffer targets" }
        val h1 = FloatArray(PinnConfig.HIDDEN_1)
        val h2 = FloatArray(PinnConfig.HIDDEN_2)
        val raw = FloatArray(PinnConfig.OUTPUT_DIM)
        forwardRaw(input, h1, h2, raw)
        val prediction = activate(raw)
        val outputGrad = outputGradients(raw, prediction, target)
        val grads = FloatArray(params.size)
        backprop(input, h1, h2, outputGrad, grads)
        update(grads)
        val dataLoss = mse(prediction, target)
        val boundaryLoss = PinnLoss.boundaryLoss(prediction)
        return PinnLossBreakdown(data = dataLoss, boundary = boundaryLoss)
    }

    fun save(file: File) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeByte(PinnConfig.WEIGHTS_VERSION)
            params.forEach(out::writeFloat)
        }
    }

    private fun forwardRaw(input: FloatArray, h1: FloatArray, h2: FloatArray, raw: FloatArray) {
        val w1 = 0
        val b1 = w1 + PinnConfig.INPUT_DIM * PinnConfig.HIDDEN_1
        val w2 = b1 + PinnConfig.HIDDEN_1
        val b2 = w2 + PinnConfig.HIDDEN_1 * PinnConfig.HIDDEN_2
        val w3 = b2 + PinnConfig.HIDDEN_2
        val b3 = w3 + PinnConfig.HIDDEN_2 * PinnConfig.OUTPUT_DIM

        for (j in 0 until PinnConfig.HIDDEN_1) {
            var sum = params[b1 + j]
            for (i in 0 until PinnConfig.INPUT_DIM) {
                sum += input[i] * params[w1 + i * PinnConfig.HIDDEN_1 + j]
            }
            h1[j] = relu(sum)
        }
        for (j in 0 until PinnConfig.HIDDEN_2) {
            var sum = params[b2 + j]
            for (i in 0 until PinnConfig.HIDDEN_1) {
                sum += h1[i] * params[w2 + i * PinnConfig.HIDDEN_2 + j]
            }
            h2[j] = relu(sum)
        }
        for (j in 0 until PinnConfig.OUTPUT_DIM) {
            var sum = params[b3 + j]
            for (i in 0 until PinnConfig.HIDDEN_2) {
                sum += h2[i] * params[w3 + i * PinnConfig.OUTPUT_DIM + j]
            }
            raw[j] = sum
        }
    }

    private fun activate(raw: FloatArray): PinnPrediction {
        return PinnPrediction(
            buffer05s = sigmoid(raw[0]) * PinnConfig.MAX_BUFFER_PACKETS,
            buffer1s = sigmoid(raw[1]) * PinnConfig.MAX_BUFFER_PACKETS,
            buffer2s = sigmoid(raw[2]) * PinnConfig.MAX_BUFFER_PACKETS,
            buffer3s = sigmoid(raw[3]) * PinnConfig.MAX_BUFFER_PACKETS,
            thermalHeadroom = sigmoid(raw[4]),
            qualityVote = tanh(raw[5])
        )
    }

    private fun outputGradients(
        raw: FloatArray,
        prediction: PinnPrediction,
        target: PinnTrainingTarget
    ): FloatArray {
        val pred = floatArrayOf(
            prediction.buffer05s,
            prediction.buffer1s,
            prediction.buffer2s,
            prediction.buffer3s,
            prediction.thermalHeadroom,
            prediction.qualityVote
        )
        val tgt = floatArrayOf(
            target.buffers[0],
            target.buffers[1],
            target.buffers[2],
            target.buffers[3],
            target.thermalHeadroom,
            target.qualityVote
        )
        val grad = FloatArray(PinnConfig.OUTPUT_DIM)
        for (i in grad.indices) {
            val base = 2f * (pred[i] - tgt[i]) / PinnConfig.OUTPUT_DIM
            grad[i] = when (i) {
                in 0..3 -> {
                    val s = (pred[i] / PinnConfig.MAX_BUFFER_PACKETS).coerceIn(0.0001f, 0.9999f)
                    base * PinnConfig.MAX_BUFFER_PACKETS * s * (1f - s)
                }
                4 -> {
                    val s = sigmoid(raw[i])
                    base * s * (1f - s)
                }
                else -> base * (1f - pred[i] * pred[i])
            }
        }
        return grad
    }

    private fun backprop(
        input: FloatArray,
        h1: FloatArray,
        h2: FloatArray,
        outputGrad: FloatArray,
        grads: FloatArray
    ) {
        val w1 = 0
        val b1 = w1 + PinnConfig.INPUT_DIM * PinnConfig.HIDDEN_1
        val w2 = b1 + PinnConfig.HIDDEN_1
        val b2 = w2 + PinnConfig.HIDDEN_1 * PinnConfig.HIDDEN_2
        val w3 = b2 + PinnConfig.HIDDEN_2
        val b3 = w3 + PinnConfig.HIDDEN_2 * PinnConfig.OUTPUT_DIM

        val h2Grad = FloatArray(PinnConfig.HIDDEN_2)
        for (i in 0 until PinnConfig.HIDDEN_2) {
            for (j in 0 until PinnConfig.OUTPUT_DIM) {
                grads[w3 + i * PinnConfig.OUTPUT_DIM + j] += h2[i] * outputGrad[j]
                h2Grad[i] += params[w3 + i * PinnConfig.OUTPUT_DIM + j] * outputGrad[j]
            }
        }
        for (j in 0 until PinnConfig.OUTPUT_DIM) {
            grads[b3 + j] += outputGrad[j]
        }

        val h1Grad = FloatArray(PinnConfig.HIDDEN_1)
        for (i in 0 until PinnConfig.HIDDEN_2) {
            val grad = if (h2[i] > 0f) h2Grad[i] else 0f
            for (j in 0 until PinnConfig.HIDDEN_1) {
                grads[w2 + j * PinnConfig.HIDDEN_2 + i] += h1[j] * grad
                h1Grad[j] += params[w2 + j * PinnConfig.HIDDEN_2 + i] * grad
            }
            grads[b2 + i] += grad
        }

        for (i in 0 until PinnConfig.HIDDEN_1) {
            val grad = if (h1[i] > 0f) h1Grad[i] else 0f
            for (j in 0 until PinnConfig.INPUT_DIM) {
                grads[w1 + j * PinnConfig.HIDDEN_1 + i] += input[j] * grad
            }
            grads[b1 + i] += grad
        }
    }

    private fun update(grads: FloatArray) {
        for (i in params.indices) {
            velocity[i] = PinnConfig.MOMENTUM * velocity[i] - PinnConfig.LEARNING_RATE * grads[i]
            params[i] += velocity[i]
        }
    }

    private fun mse(prediction: PinnPrediction, target: PinnTrainingTarget): Float {
        val pred = prediction.buffers + floatArrayOf(prediction.thermalHeadroom, prediction.qualityVote)
        val tgt = target.buffers.copyOf(4) + floatArrayOf(target.thermalHeadroom, target.qualityVote)
        var sum = 0f
        for (i in pred.indices) {
            val error = pred[i] - tgt[i]
            sum += error * error
        }
        return sum / pred.size
    }

    companion object {
        fun create(seed: Long = System.nanoTime()): PinnNetwork {
            val random = Random(seed)
            val params = FloatArray(PinnConfig.PARAMETER_COUNT)
            fillXavier(params, random)
            return PinnNetwork(params)
        }

        fun loadOrCreate(file: File): PinnNetwork {
            if (!file.exists()) return create()
            return runCatching {
                DataInputStream(file.inputStream().buffered()).use { input ->
                    val version = input.readUnsignedByte()
                    require(version == PinnConfig.WEIGHTS_VERSION)
                    val params = FloatArray(PinnConfig.PARAMETER_COUNT)
                    for (i in params.indices) {
                        params[i] = input.readFloat()
                    }
                    PinnNetwork(params)
                }
            }.getOrElse { create() }
        }

        private fun fillXavier(params: FloatArray, random: Random) {
            var offset = 0
            offset = fillMatrix(params, offset, PinnConfig.INPUT_DIM, PinnConfig.HIDDEN_1, random)
            offset = fillBias(params, offset, PinnConfig.HIDDEN_1)
            offset = fillMatrix(params, offset, PinnConfig.HIDDEN_1, PinnConfig.HIDDEN_2, random)
            offset = fillBias(params, offset, PinnConfig.HIDDEN_2)
            offset = fillMatrix(params, offset, PinnConfig.HIDDEN_2, PinnConfig.OUTPUT_DIM, random)
            fillBias(params, offset, PinnConfig.OUTPUT_DIM)
        }

        private fun fillMatrix(params: FloatArray, offset: Int, fanIn: Int, fanOut: Int, random: Random): Int {
            val limit = sqrt(6.0 / (fanIn + fanOut)).toFloat()
            var cursor = offset
            repeat(fanIn * fanOut) {
                params[cursor++] = (random.nextFloat() * 2f - 1f) * limit
            }
            return cursor
        }

        private fun fillBias(params: FloatArray, offset: Int, count: Int): Int {
            for (i in 0 until count) {
                params[offset + i] = 0f
            }
            return offset + count
        }
    }
}

private fun relu(value: Float): Float = if (value > 0f) value else 0f

private fun sigmoid(value: Float): Float {
    val clipped = value.coerceIn(-30f, 30f)
    return (1.0 / (1.0 + exp(-clipped.toDouble()))).toFloat()
}
