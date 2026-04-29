package io.carmo.airplay.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.absoluteValue
import kotlin.math.max

class TrafficMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bandwidthBitsPerSecond = FloatArray(BUCKET_COUNT)
    private val latencyMs = FloatArray(BUCKET_COUNT)
    private val graphRect = RectF()
    private val linePath = Path()
    private val lock = Any()

    private var currentSample = -1L
    private var currentBytes = 0L
    private var currentLatencyTotal = 0L
    private var currentLatencySamples = 0

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(110, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val gridShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(95, 0, 0, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val darkStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 0, 0, 0)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lightStrokePaint = Paint(darkStrokePaint).apply {
        color = Color.argb(205, 255, 255, 255)
        strokeWidth = 4f
    }
    private val bandwidthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 56, 184, 99)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val latencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 220, 74, 74)
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
    }
    private val textShadowPaint = Paint(textPaint).apply {
        color = Color.argb(230, 0, 0, 0)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val ticker = object : Runnable {
        override fun run() {
            rotateToCurrentSample()
            invalidate()
            if (visibility == VISIBLE) {
                postDelayed(this, TICK_MS)
            }
        }
    }

    init {
        setWillNotDraw(false)
    }

    fun recordTraffic(byteCount: Int) {
        if (byteCount <= 0) {
            return
        }
        synchronized(lock) {
            rotateToSample(currentSampleIndex())
            currentBytes += byteCount.toLong()
        }
    }

    fun recordLatency(latency: Long) {
        if (latency < 0L) {
            return
        }
        synchronized(lock) {
            rotateToSample(currentSampleIndex())
            currentLatencyTotal += latency
            currentLatencySamples++
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(ticker)
        if (visibility == VISIBLE) {
            post(ticker)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val snapshots = synchronized(lock) {
            rotateToSample(currentSampleIndex())
            bandwidthBitsPerSecond.copyOf() to latencyMs.copyOf()
        }
        val bandwidthSnapshot = snapshots.first
        val latencySnapshot = snapshots.second

        val padding = 10f * resources.displayMetrics.density
        val topText = padding + textPaint.textSize
        graphRect.set(padding, topText + padding, width - padding, height - padding)
        if (graphRect.width() <= 0f || graphRect.height() <= 0f) {
            return
        }

        drawTextWithOutline(
            canvas,
            "Media ${formatBitsPerSecond(bandwidthSnapshot.last())}",
            padding,
            topText
        )
        drawTextWithOutline(
            canvas,
            "Latency ${latencySnapshot.last().toInt()} ms",
            padding,
            topText + textPaint.textSize + 2f * resources.displayMetrics.density
        )

        drawGrid(canvas)
        drawSeries(canvas, bandwidthSnapshot, max(1f, bandwidthSnapshot.maxOrNull() ?: 1f), bandwidthPaint)
        drawSeries(canvas, latencySnapshot, max(50f, latencySnapshot.maxOrNull() ?: 50f), latencyPaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val middleY = graphRect.centerY()
        canvas.drawLine(graphRect.left, middleY, graphRect.right, middleY, gridShadowPaint)
        canvas.drawLine(graphRect.left, graphRect.bottom, graphRect.right, graphRect.bottom, gridShadowPaint)
        canvas.drawLine(graphRect.left, middleY, graphRect.right, middleY, gridPaint)
        canvas.drawLine(graphRect.left, graphRect.bottom, graphRect.right, graphRect.bottom, gridPaint)
    }

    private fun drawSeries(canvas: Canvas, values: FloatArray, maxValue: Float, paint: Paint) {
        linePath.reset()
        values.forEachIndexed { index, value ->
            val x = graphRect.left + graphRect.width() * index / (BUCKET_COUNT - 1)
            val y = graphRect.bottom - graphRect.height() * (value / maxValue).coerceIn(0f, 1f)
            if (index == 0) {
                linePath.moveTo(x, y)
            } else {
                linePath.lineTo(x, y)
            }
        }
        canvas.drawPath(linePath, darkStrokePaint)
        canvas.drawPath(linePath, lightStrokePaint)
        canvas.drawPath(linePath, paint)
    }

    private fun drawTextWithOutline(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, textShadowPaint)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun rotateToCurrentSample() {
        synchronized(lock) {
            rotateToSample(currentSampleIndex())
        }
    }

    private fun currentSampleIndex(): Long = SystemClock.elapsedRealtime() / SAMPLE_MS

    private fun rotateToSample(sample: Long) {
        if (currentSample == -1L) {
            currentSample = sample
            return
        }
        val elapsedSamples = sample - currentSample
        if (elapsedSamples <= 0L) {
            return
        }

        val completedBandwidth = bytesToBitsPerSecond(currentBytes)
        val completedLatency = if (currentLatencySamples > 0) {
            currentLatencyTotal.toFloat() / currentLatencySamples
        } else {
            0f
        }
        val shifts = elapsedSamples.coerceAtMost(BUCKET_COUNT.toLong()).toInt()
        repeat(shifts) { shift ->
            shiftLeft(bandwidthBitsPerSecond)
            shiftLeft(latencyMs)
            if (shift == 0 && elapsedSamples <= BUCKET_COUNT) {
                bandwidthBitsPerSecond[BUCKET_COUNT - 1] = completedBandwidth
                latencyMs[BUCKET_COUNT - 1] = completedLatency
            }
        }
        currentSample = sample
        currentBytes = 0L
        currentLatencyTotal = 0L
        currentLatencySamples = 0
    }

    private fun shiftLeft(values: FloatArray) {
        for (index in 0 until values.lastIndex) {
            values[index] = values[index + 1]
        }
        values[values.lastIndex] = 0f
    }

    private fun bytesToBitsPerSecond(bytes: Long): Float =
        bytes.toFloat() * BITS_PER_BYTE * MILLIS_PER_SECOND / SAMPLE_MS

    private fun formatBitsPerSecond(value: Float): String {
        val absoluteValue = value.absoluteValue
        return when {
            absoluteValue < KILOBIT -> "${value.toInt()} b/s"
            absoluteValue < MEGABIT -> "${formatScaledValue(value / KILOBIT)} kb/s"
            else -> "${formatScaledValue(value / MEGABIT)} Mb/s"
        }
    }

    private fun formatScaledValue(value: Float): String {
        val absoluteValue = value.absoluteValue
        return if (absoluteValue >= 100f) {
            String.format("%.0f", value)
        } else {
            String.format("%.1f", value)
        }
    }

    companion object {
        private const val BUCKET_COUNT = 30
        private const val SAMPLE_MS = 500L
        private const val TICK_MS = SAMPLE_MS
        private const val MILLIS_PER_SECOND = 1_000L
        private const val BITS_PER_BYTE = 8f
        private const val KILOBIT = 1_000f
        private const val MEGABIT = 1_000_000f
    }
}
