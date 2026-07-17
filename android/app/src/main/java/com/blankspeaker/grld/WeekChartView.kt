package com.blankspeaker.grld

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Multi-day stacked bar chart (1–3 weeks) with fully rounded stadium bars.
 * On wide screens (tablet / unfolded foldable) the host feeds 14 or 21 days.
 */
class WeekChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var bars: List<ChartBar> = emptyList()
    private var weeksShown: Int = 1
    private var palette: Map<Int, Int> = emptyMap()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.FILL
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFAAAAAA.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
    }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEEEEEE.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
        isFakeBoldText = true
    }
    private val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF888888.toInt()
        textAlign = Paint.Align.CENTER
        textSize = sp(9f)
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val weekDividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33FFFFFF
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    companion object {
        /**
         * Previous SuperGrok week (mid-day reset fragment).
         * Keep in sync with the "Before Reset" legend dot in MainActivity.
         */
        const val PRE_RESET_COLOR = 0xFF424242.toInt() // black / dark gray
    }

    fun setBars(
        bars: List<ChartBar>,
        weeksShown: Int = 1,
        palette: Map<Int, Int> = emptyMap()
    ) {
        this.bars = bars
        this.weeksShown = weeksShown.coerceIn(1, 3)
        this.palette = palette.ifEmpty {
            NotifPalette.indexMap(bars.flatMap { b -> b.segments.map { it.product } })
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val minH = suggestedMinimumHeight.coerceAtLeast(dp(100f).toInt())
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            MeasureSpec.AT_MOST -> MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(minH)
            else -> minH
        }
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bars.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val dayNumH = dp(12f)
        val weekdayH = dp(14f)
        val valueH = dp(14f)
        val labelH = dayNumH + weekdayH
        val topPad = dp(2f)
        val barAreaH = h - labelH - valueH - topPad
        if (barAreaH <= 0f) return

        val n = max(bars.size, 1)
        // Thin bars when showing many days so 14/21 still fit cleanly
        val slotW = w / n
        val maxBar = if (n > 14) dp(12f) else if (n > 7) dp(14f) else dp(16f)
        val minBar = if (n > 14) dp(5f) else dp(7f)
        val barW = min(maxBar, max(minBar, slotW - dp(4f)))
        val cornerR = barW / 2f

        // Scale text down slightly for dense multi-week views
        val textScale = when {
            n > 14 -> 0.85f
            n > 7 -> 0.92f
            else -> 1f
        }
        valuePaint.textSize = sp(9f) * textScale
        dayPaint.textSize = sp(9f) * textScale
        numPaint.textSize = sp(9f) * textScale

        val maxDelta = max(
            bars.maxOfOrNull { max(it.dayDelta, it.segments.sumOf { s -> s.delta }) } ?: 1,
            10
        ).toFloat()

        // Week separators (between each block of ~7 calendar days by index groups)
        if (weeksShown > 1 && n >= 7) {
            val daysPerWeek = 7
            // Approximate: every 7th bar index boundary (may drift if reset fragments add extra bars)
            var i = 0
            var dayCount = 0
            var lastDay: String? = null
            while (i < bars.size) {
                val d = bars[i].dayKey
                if (d != lastDay) {
                    if (lastDay != null) dayCount++
                    lastDay = d
                    // Draw divider before starting a new week block (every 7 calendar days)
                    if (dayCount > 0 && dayCount % daysPerWeek == 0) {
                        val x = i * slotW
                        canvas.drawLine(x, labelH, x, h - dp(2f), weekDividerPaint)
                    }
                }
                i++
            }
        }

        for ((i, bar) in bars.withIndex()) {
            val slotX = i * slotW
            val segSum = bar.segments.sumOf { it.delta }
            val total = max(bar.dayDelta, segSum)
            val drawH = if (total > 0) max(dp(4f), barAreaH * total / maxDelta) else 0f
            val barX = slotX + (slotW - barW) / 2f
            val barY = labelH + valueH

            canvas.drawRoundRect(
                RectF(barX, barY, barX + barW, barY + barAreaH),
                cornerR, cornerR, trackPaint
            )

            if (drawH > 0) {
                val fillBottom = barY + barAreaH
                // Pre-reset = previous SuperGrok week (always solid black, never category colors)
                if (bar.isPreResetFragment) {
                    val fillTop = fillBottom - drawH
                    fillPaint.color = PRE_RESET_COLOR
                    canvas.drawRoundRect(
                        RectF(barX, fillTop, barX + barW, fillBottom),
                        cornerR, cornerR, fillPaint
                    )
                } else if (bar.segments.isNotEmpty() && segSum > 0) {
                    // Stacked rounded capsules with gaps — same look as the horizontal
                    // multi-segment usage bar (not a single clipped stack).
                    val ordered = bar.segments
                        .filter { it.delta > 0 }
                        .sortedBy { it.product }
                    val nSeg = ordered.size
                    val gap = if (nSeg > 1) dp(2.5f) else 0f
                    val totalGap = gap * (nSeg - 1).coerceAtLeast(0)
                    val usableH = (drawH - totalGap).coerceAtLeast(dp(3f))
                    var yBottom = fillBottom
                    ordered.forEachIndexed { idx, seg ->
                        var segH = usableH * seg.delta / segSum
                        // Tiny contributions still get a short capsule
                        if (seg.delta > 0) segH = max(dp(3f), segH)
                        val top = yBottom - segH
                        fillPaint.color = NotifPalette.argb(seg.product, palette)
                        // Fully rounded stadium capsule per category
                        val r = min(cornerR, segH / 2f)
                        canvas.drawRoundRect(
                            RectF(barX, top, barX + barW, yBottom),
                            r, r, fillPaint
                        )
                        yBottom = top - if (idx < nSeg - 1) gap else 0f
                    }
                } else {
                    val fillTop = fillBottom - drawH
                    fillPaint.color = NotifPalette.defaultArgb
                    canvas.drawRoundRect(
                        RectF(barX, fillTop, barX + barW, fillBottom),
                        cornerR, cornerR, fillPaint
                    )
                }
            }

            val cx = slotX + slotW / 2f
            // On dense charts, skip % labels for empty days to reduce clutter
            val valText = when {
                total > 0 -> "${if (bar.dayDelta > 0) bar.dayDelta else total}%"
                n > 14 -> ""
                else -> "·"
            }
            if (valText.isNotEmpty()) {
                canvas.drawText(valText, cx, labelH + valueH - dp(2f), valuePaint)
            }
            // Weekday letter; for multi-week use first letter only when dense
            val weekday = if (n > 10 && bar.weekdayLabel.length > 1) {
                bar.weekdayLabel.take(1)
            } else {
                bar.weekdayLabel
            }
            canvas.drawText(weekday, cx, dayNumH + weekdayH - dp(2f), dayPaint)
            canvas.drawText(
                DailyHistoryStore.dayOfMonth(bar.dayKey),
                cx,
                dayNumH - dp(1f),
                numPaint
            )
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
