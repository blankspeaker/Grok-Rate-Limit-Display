package com.blankspeaker.grld

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.drawable.IconCompat
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Notification / status icons.
 *
 * Live Lucide gauge (same paths as Tampermonkey setGaugeSVG):
 *   needle: m12 14 4-4
 *   arc:    M3.34 19a10 10 0 1 1 17.32 0
 *
 * Needle tracks **remaining** %: 100% remaining → right, 0% remaining → left.
 * Color by remaining: >25% white · 5–25% yellow→orange · ≤5% red.
 * (Status-bar smallIcon is monochrome on many OEMs; color still helps tray / largeIcon.)
 */
object PercentIconFactory {

    private const val SIZE = 256

    fun icon(context: Context, percent: Int?): IconCompat {
        // percent is typically used% from the service — convert for needle
        val remaining = percent?.let { (100 - it).coerceIn(0, 100) }
        return IconCompat.createWithBitmap(gaugeBitmap(remaining, SIZE, monochrome = true))
    }

    fun logoIcon(context: Context): IconCompat {
        return IconCompat.createWithBitmap(gaugeBitmap(remaining = null, size = SIZE, monochrome = true))
    }

    fun bitmapLogoOnly(context: Context): Bitmap =
        gaugeBitmap(remaining = null, size = SIZE, monochrome = true)

    /**
     * Clean Lucide gauge — same math as Mac [GaugeIcon].
     * Always draws on a **transparent** background (no fill).
     *
     * @param remaining 100 = full (needle right), 0 = empty (needle left); null = default lucide pose
     * @param monochrome force white (status-bar alpha mask — system tints black in light mode)
     * @param strokeColor override stroke color (e.g. black for light-mode tray RemoteViews)
     */
    fun gaugeBitmap(
        remaining: Int?,
        size: Int = SIZE,
        monochrome: Boolean = false,
        strokeColor: Int? = null
    ): Bitmap {
        val s = size.coerceAtLeast(24)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // Explicit clear — never a solid black/white plate
        canvas.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

        val rem = remaining?.coerceIn(0, 100)
        val color = strokeColor
            ?: if (monochrome) Color.WHITE
            else colorForRemaining(rem)

        // Match Mac GaugeIcon: pad 8% of size, Lucide stroke 2 in 24 viewBox
        val pad = s * 0.08f
        val scale = (s - 2f * pad) / 24f
        // Slightly bolder so the needle stays visible when downscaled to 24–44dp
        val stroke = max(1.6f, 2.15f * scale)

        fun sx(x: Float) = pad + x * scale
        fun sy(y: Float) = pad + y * scale // Canvas y-down matches SVG

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isFilterBitmap = true
        }

        val arc = arcGeometry()
        val path = Path()
        val steps = max(40, s)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val th = arc.th1 + arc.dth * t
            val X = sx(arc.cx + arc.rx * cos(th))
            val Y = sy(arc.cy + arc.ry * sin(th))
            if (i == 0) path.moveTo(X, Y) else path.lineTo(X, Y)
        }
        canvas.drawPath(path, paint)

        // Needle: remaining 100 → right end of arc; 0 → left. Tip on arc rim (Mac).
        val pivotX = sx(12f)
        val pivotY = sy(14f)
        val (nx, ny) = if (rem != null) {
            val t = rem / 100f
            val th = arc.th1 + arc.dth * t
            Pair(
                sx(arc.cx + arc.rx * cos(th)),
                sy(arc.cy + arc.ry * sin(th))
            )
        } else {
            Pair(sx(16f), sy(10f)) // default lucide m12 14 → 16 10
        }
        // Needle a touch thicker than the arc so motion is obvious at tray size
        paint.strokeWidth = stroke * 1.2f
        canvas.drawLine(pivotX, pivotY, nx, ny, paint)
        return bmp
    }

    /** True when the device is in light (day) UI mode. */
    fun isLightUi(context: Context): Boolean {
        val night = context.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * Tray RemoteViews gauge: transparent bg, **black** strokes in light mode,
     * white in dark mode. Live needle from remaining %.
     */
    fun trayGaugeBitmap(context: Context, remaining: Int?, size: Int = 192): Bitmap {
        val stroke = if (isLightUi(context)) Color.BLACK else Color.WHITE
        return gaugeBitmap(
            remaining = remaining,
            size = size,
            monochrome = false,
            strokeColor = stroke
        )
    }

    /** Status-bar: huge % (legacy path when Live Update needs a number glyph). */
    fun statusBarPercentIcon(context: Context, percent: Int): Bitmap {
        val size = SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)

        val text = when {
            percent >= 100 -> "99"
            percent < 0 -> "0"
            else -> percent.toString()
        }

        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isSubpixelText = true
        }
        val pad = size * 0.08f
        var lo = 8f
        var hi = size * 1.4f
        var best = lo
        repeat(32) {
            val mid = (lo + hi) / 2f
            tp.textSize = mid
            val tw = tp.measureText(text)
            val fm = tp.fontMetrics
            val th = fm.descent - fm.ascent
            if (tw <= size - pad * 2 && th <= size - pad * 2) {
                best = mid
                lo = mid
            } else hi = mid
        }
        tp.textSize = best
        val fm = tp.fontMetrics
        val cy = size / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, size / 2f, cy, tp)
        return bmp
    }

    fun bitmap(context: Context, percent: Int?): Bitmap {
        // Used% in → remaining for needle
        val remaining = percent?.let { (100 - it).coerceIn(0, 100) }
        return gaugeBitmap(remaining, SIZE, monochrome = true)
    }

    fun colorForUsed(used: Int?): Int {
        val u = used ?: return Color.GRAY
        return colorForRemaining((100 - u).coerceIn(0, 100))
    }

    fun colorForRemaining(rem: Int?): Int {
        val r = rem ?: return Color.WHITE
        if (r > 25) return Color.WHITE
        if (r <= 5) return 0xFFE53935.toInt() // red
        // 5..25 → orange at 5, yellow at 25
        val t = (r - 5) / 20f
        // lerp orange #FF9800 → yellow #FFEB3B
        val oR = 0xFF
        val oG = 0x98
        val oB = 0x00
        val yR = 0xFF
        val yG = 0xEB
        val yB = 0x3B
        val R = (oR + (yR - oR) * t).toInt().coerceIn(0, 255)
        val G = (oG + (yG - oG) * t).toInt().coerceIn(0, 255)
        val B = (oB + (yB - oB) * t).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (R shl 16) or (G shl 8) or B
    }

    fun categoryDotBitmap(color: Int, sizePx: Int = 48): Bitmap {
        val s = sizePx.coerceAtLeast(12)
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val r = s / 2f
        canvas.drawCircle(r, r, r * 0.92f, p)
        return bmp
    }

    fun categoryColoredTextLegend(products: List<ProductUsage>, sizePx: Int = 512): Bitmap {
        val size = sizePx.coerceAtLeast(256)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        if (products.isEmpty()) return bmp

        val pad = size * 0.06f
        val n = products.size.coerceAtLeast(1)
        val rowH = (size - pad * 2) / n
        val labels = products.map { p ->
            val name = p.name.ifBlank { ProductColors.displayName(p.product) }
            "$name ${p.usagePercent}%"
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isSubpixelText = true
            textAlign = Paint.Align.LEFT
        }
        var textSize = rowH * 0.72f
        paint.textSize = textSize
        val maxW = size - pad * 2
        while (textSize > 22f && labels.any { paint.measureText(it) > maxW }) {
            textSize -= 2f
            paint.textSize = textSize
        }
        var y = pad
        products.forEachIndexed { i, p ->
            paint.color = ProductColors.forId(p.product)
            val fm = paint.fontMetrics
            val cy = y + rowH / 2f
            val ty = cy - (fm.ascent + fm.descent) / 2f
            canvas.drawText(labels[i], pad, ty, paint)
            y += rowH
        }
        return bmp
    }

    /**
     * Multi-color usage bar.
     *
     * @param fullHeight Main app: one thick **stadium** (straight long edges,
     *   rounded outer tips only). Segments are side-by-side slabs with square
     *   joins — not separate oval capsules. Notif/widget: thin centered pills
     *   with gaps (unchanged).
     */
    fun usageBarBitmap(
        products: List<ProductUsage>,
        usedPercent: Int,
        width: Int = 1024,
        height: Int = 256,
        opaqueBackground: Boolean = false,
        palette: Map<Int, Int> = NotifPalette.indexMapFromUsage(products),
        fullHeight: Boolean = false
    ): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (opaqueBackground) {
            canvas.drawColor(0xFF1A1A1A.toInt())
        } else {
            canvas.drawColor(Color.TRANSPARENT)
        }

        // Main app: thick stadium (almost full height). Notif/widget: thin centered pill.
        val barH: Float
        val barTop: Float
        val pad: Float
        if (fullHeight) {
            // Tiny vertical inset so AA doesn't clip
            val vPad = (height * 0.04f).coerceIn(1f, 6f)
            barH = (height - 2f * vPad).coerceAtLeast(1f)
            barTop = vPad
            pad = (width * 0.004f).coerceIn(0f, 4f)
        } else {
            barH = if (height <= 128) height * 0.55f else height * 0.38f
            barTop = (height - barH) / 2f
            pad = if (height <= 128) width * 0.01f else width * 0.06f
        }
        val barLeft = pad
        val barRight = width - pad
        val barW = (barRight - barLeft).coerceAtLeast(1f)
        val r = barH / 2f // stadium tip radius (half thickness)
        // Gap only for thin notif/widget capsules — main bar is continuous
        val gap = if (fullHeight) 0f else (width * 0.008f).coerceIn(3f, 10f)

        val used = usedPercent.coerceIn(0, 100)
        val usedW = barW * used / 100f
        val remW = barW - usedW
        val positive = products.filter { it.usagePercent > 0 }.sortedBy { it.product }

        // Build segment widths for the used portion
        val segs = mutableListOf<Pair<Float, Int>>() // width, color
        if (used > 0) {
            if (positive.isEmpty()) {
                segs += usedW to NotifPalette.defaultArgb
            } else {
                val sum = positive.sumOf { it.usagePercent }.coerceAtLeast(1).toFloat()
                var allocated = 0f
                positive.forEachIndexed { i, prod ->
                    val w = if (i == positive.lastIndex) {
                        (usedW - allocated).coerceAtLeast(0f)
                    } else {
                        (usedW * (prod.usagePercent / sum)).also { allocated += it }
                    }
                    if (w > 0.5f) {
                        segs += w to NotifPalette.argb(prod.product, palette)
                    }
                }
            }
        }
        if (remW > 0.5f) {
            segs += remW to 0xFF3A3A3A.toInt()
        }

        val n = segs.size
        if (n == 0) {
            val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF3A3A3A.toInt() }
            canvas.drawRoundRect(RectF(barLeft, barTop, barRight, barTop + barH), r, r, track)
            return bmp
        }
        val totalGap = gap * (n - 1).coerceAtLeast(0)
        val rawSum = segs.fold(0f) { acc, s -> acc + s.first }.coerceAtLeast(1f)
        val scale = if (barW > totalGap) (barW - totalGap) / rawSum else 1f

        var x = barLeft
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        segs.forEachIndexed { i, (rawW, color) ->
            val w = if (fullHeight) {
                // True proportional width — no min-width that turns short segs into ovals
                (rawW * scale).coerceAtLeast(1f)
            } else {
                // Tiny notif segments still get a short pill so 2% isn't a hairline
                (rawW * scale).coerceAtLeast(barH * 0.85f)
            }
            val right = min(x + w, barRight)
            if (right > x + 0.5f) {
                paint.color = color
                val rect = RectF(x, barTop, right, barTop + barH)
                if (fullHeight) {
                    // Vertical bars on their side: straight long edges; only outer
                    // tips of the whole bar are rounded. Middles are square slabs.
                    val roundLeft = i == 0
                    val roundRight = i == n - 1
                    drawHorizontalStadiumSegment(canvas, rect, r, roundLeft, roundRight, paint)
                } else {
                    canvas.drawRoundRect(rect, r, r, paint)
                }
            }
            x = right + if (i < n - 1) gap else 0f
        }
        return bmp
    }

    /**
     * Horizontal stadium segment: top/bottom always straight; left and/or right
     * ends half-rounded (like a week-chart bar rotated 90°).
     */
    private fun drawHorizontalStadiumSegment(
        canvas: Canvas,
        rect: RectF,
        radius: Float,
        roundLeft: Boolean,
        roundRight: Boolean,
        paint: Paint
    ) {
        val rr = min(radius, min(rect.height() / 2f, rect.width() / 2f))
        if (!roundLeft && !roundRight) {
            canvas.drawRect(rect, paint)
            return
        }
        if (roundLeft && roundRight) {
            canvas.drawRoundRect(rect, rr, rr, paint)
            return
        }
        // Per-corner radii: [TL-x, TL-y, TR-x, TR-y, BR-x, BR-y, BL-x, BL-y]
        val radii = floatArrayOf(
            if (roundLeft) rr else 0f, if (roundLeft) rr else 0f,
            if (roundRight) rr else 0f, if (roundRight) rr else 0f,
            if (roundRight) rr else 0f, if (roundRight) rr else 0f,
            if (roundLeft) rr else 0f, if (roundLeft) rr else 0f
        )
        val path = Path()
        path.addRoundRect(rect, radii, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }

    // SVG arc: M3.34 19 a10 10 0 1 1 17.32 0
    private data class Arc(
        val cx: Float, val cy: Float, val rx: Float, val ry: Float,
        val th1: Float, val dth: Float
    )

    private fun arcGeometry(): Arc {
        val x1 = 3.34f
        val y1 = 19f
        val x2 = 20.66f
        val y2 = 19f
        val rx0 = 10f
        val ry0 = 10f
        val fa = true
        val fs = true

        val dx = (x1 - x2) / 2f
        val dy = (y1 - y2) / 2f
        var rx = rx0
        var ry = ry0
        var rx2 = rx * rx
        var ry2 = ry * ry
        val x1p = dx
        val y1p = dy
        val lam = x1p * x1p / rx2 + y1p * y1p / ry2
        if (lam > 1f) {
            val s = sqrt(lam)
            rx *= s
            ry *= s
            rx2 = rx * rx
            ry2 = ry * ry
        }
        val num = max(
            0f,
            (rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p) /
                (rx2 * y1p * y1p + ry2 * x1p * x1p)
        )
        var coef = sqrt(num)
        if (fa == fs) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cx = (x1 + x2) / 2f + cxp
        val cy = (y1 + y2) / 2f + cyp

        fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val sign = if (ux * vy - uy * vx < 0) -1f else 1f
            val dot = ux * vx + uy * vy
            val nu = sqrt(ux * ux + uy * uy)
            val nv = sqrt(vx * vx + vy * vy)
            val c = max(-1f, min(1f, dot / (nu * nv)))
            return sign * acos(c)
        }
        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val th1 = angle(1f, 0f, ux, uy)
        var dth = angle(ux, uy, vx, vy)
        if (!fs && dth > 0) dth -= (2 * PI).toFloat()
        if (fs && dth < 0) dth += (2 * PI).toFloat()
        return Arc(cx, cy, rx, ry, th1, dth)
    }
}
