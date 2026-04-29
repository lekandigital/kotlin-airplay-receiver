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
import kotlin.math.max

class TrafficMonitorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val bandwidthMbps = FloatArray(BUCKET_COUNT)
    private val latencyMs = FloatArray(BUCKET_COUNT)
    private val graphRect = RectF()
    private val linePath = Path()
    private val lock = Any()

    private var currentSecond = -1L
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
        color = Color.argb(190, 0, 0, 0)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val lightStrokePaint = Paint(darkStrokePaint).apply {
        color = Color.argb(210, 255, 255, 255)
    }
    private val bandwidthPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 255, 255, 255)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val latencyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 40, 40, 40)
        strokeWidth = 2f
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
            rotateToCurrentSecond()
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
            rotateToSecond(SystemClock.elapsedRealtime() / MILLIS_PER_SECOND)
            currentBytes += byteCount.toLong()
            bandwidthMbps[BUCKET_COUNT - 1] = bytesToMegabits(currentBytes)
        }
        postInvalidate()
    }

    fun recordLatency(latency: Long) {
        if (latency < 0L) {
            return
        }
        synchronized(lock) {
            rotateToSecond(SystemClock.elapsedRealtime() / MILLIS_PER_SECOND)
            currentLatencyTotal += latency
            currentLatencySamples++
            latencyMs[BUCKET_COUNT - 1] = currentLatencyTotal.toFloat() / currentLatencySamples
        }
        postInvalidate()
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
            rotateToSecond(SystemClock.elapsedRealtime() / MILLIS_PER_SECOND)
            bandwidthMbps.copyOf() to latencyMs.copyOf()
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
            "Media ${formatMbps(bandwidthSnapshot.last())} Mb/s",
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
        drawSeries(canvas, bandwidthSnapshot, max(1f, bandwidthSnapshot.maxOrNull() ?: 1f), bandwidthPaint, darkStrokePaint)
        drawSeries(canvas, latencySnapshot, max(50f, latencySnapshot.maxOrNull() ?: 50f), latencyPaint, lightStrokePaint)
    }

    private fun drawGrid(canvas: Canvas) {
        val middleY = graphRect.centerY()
        canvas.drawLine(graphRect.left, middleY, graphRect.right, middleY, gridShadowPaint)
        canvas.drawLine(graphRect.left, graphRect.bottom, graphRect.right, graphRect.bottom, gridShadowPaint)
        canvas.drawLine(graphRect.left, middleY, graphRect.right, middleY, gridPaint)
        canvas.drawLine(graphRect.left, graphRect.bottom, graphRect.right, graphRect.bottom, gridPaint)
    }

    private fun drawSeries(canvas: Canvas, values: FloatArray, maxValue: Float, paint: Paint, outlinePaint: Paint) {
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
        canvas.drawPath(linePath, outlinePaint)
        canvas.drawPath(linePath, paint)
    }

    private fun drawTextWithOutline(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text, x, y, textShadowPaint)
        canvas.drawText(text, x, y, textPaint)
    }

    private fun rotateToCurrentSecond() {
        synchronized(lock) {
            rotateToSecond(SystemClock.elapsedRealtime() / MILLIS_PER_SECOND)
        }
    }

    private fun rotateToSecond(second: Long) {
        if (currentSecond == -1L) {
            currentSecond = second
            return
        }
        val elapsedSeconds = second - currentSecond
        if (elapsedSeconds <= 0L) {
            return
        }

        val shifts = elapsedSeconds.coerceAtMost(BUCKET_COUNT.toLong()).toInt()
        repeat(shifts) {
            shiftLeft(bandwidthMbps)
            shiftLeft(latencyMs)
        }
        currentSecond = second
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

    private fun bytesToMegabits(bytes: Long): Float = bytes * BITS_PER_BYTE / MEGABIT

    private fun formatMbps(value: Float): String = String.format("%.1f", value)

    companion object {
        private const val BUCKET_COUNT = 30
        private const val TICK_MS = 1_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val BITS_PER_BYTE = 8f
        private const val MEGABIT = 1_000_000f
    }
}
