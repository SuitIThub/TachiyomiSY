package eu.kanade.tachiyomi.data.translator.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class BubbleShape {
    ROUNDED_RECT,
    ELLIPSE,
    RECT,
}

data class SpeechBubbleRegion(
    /** Outer shape used for background fill / clipping. */
    val bounds: RectF,
    val shape: BubbleShape,
    /** Corner radius for rounded rect (fraction of min side when shape is ROUNDED_RECT). */
    val cornerRadius: Float,
    /** True when flood-fill found a plausible bubble fill. */
    val detected: Boolean,
)

/**
 * Detects speech-bubble fill around an OCR text box via luminance-based region growing,
 * then approximates the region as ellipse / rounded rect for layout.
 */
class SpeechBubbleDetector {

    fun detect(bitmap: Bitmap, textBox: Rect): SpeechBubbleRegion {
        val pageW = bitmap.width
        val pageH = bitmap.height
        val seed = sampleSeedLuminance(bitmap, textBox)
        val search = expandSearchBox(textBox, pageW, pageH)
        val mask = floodFill(bitmap, textBox, search, seed)

        if (mask == null) {
            return fallbackRegion(textBox, pageW, pageH, detected = false)
        }

        val (minX, minY, maxX, maxY, count) = mask
        if (count < max(24, textBox.width() * textBox.height() / 8)) {
            return fallbackRegion(textBox, pageW, pageH, detected = false)
        }

        // Keep bubble from exploding across the whole panel.
        val textArea = textBox.width().toFloat() * textBox.height()
        val bubbleArea = (maxX - minX + 1f) * (maxY - minY + 1f)
        // Oversized fills create empty overlays; prefer OCR-tight fallback.
        if (bubbleArea > textArea * 5f || bubbleArea > pageW * pageH * 0.22f) {
            return fallbackRegion(textBox, pageW, pageH, detected = false)
        }

        val bounds = RectF(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f)
        // Slight inset so we stay inside the outline.
        val inset = min(bounds.width(), bounds.height()) * 0.04f
        bounds.inset(inset, inset)

        val shape = classifyShape(bitmap, bounds, seed)
        val corner = when (shape) {
            BubbleShape.ROUNDED_RECT -> min(bounds.width(), bounds.height()) * 0.22f
            BubbleShape.ELLIPSE -> min(bounds.width(), bounds.height()) * 0.5f
            BubbleShape.RECT -> min(bounds.width(), bounds.height()) * 0.06f
        }

        return SpeechBubbleRegion(
            bounds = bounds,
            shape = shape,
            cornerRadius = corner,
            detected = true,
        )
    }

    fun toPath(region: SpeechBubbleRegion): Path {
        val path = Path()
        when (region.shape) {
            BubbleShape.ELLIPSE -> path.addOval(region.bounds, Path.Direction.CW)
            BubbleShape.ROUNDED_RECT -> path.addRoundRect(
                region.bounds,
                region.cornerRadius,
                region.cornerRadius,
                Path.Direction.CW,
            )
            BubbleShape.RECT -> path.addRect(region.bounds, Path.Direction.CW)
        }
        return path
    }

    /**
     * Horizontal inset available for a text line centered at [centerY] inside the bubble.
     */
    fun lineWidthAt(region: SpeechBubbleRegion, centerY: Float, padding: Float): Float {
        val b = region.bounds
        val usableHeight = b.height() - padding * 2
        if (usableHeight <= 1f) return 0f
        val y = centerY.coerceIn(b.top + padding, b.bottom - padding)
        return when (region.shape) {
            BubbleShape.ELLIPSE -> {
                val cy = b.centerY()
                val a = (b.width() / 2f) - padding
                val bh = (b.height() / 2f) - padding
                if (a <= 1f || bh <= 1f) return 0f
                val dy = (y - cy) / bh
                if (abs(dy) >= 1f) return 0f
                2f * a * sqrt(1f - dy * dy)
            }
            BubbleShape.ROUNDED_RECT -> {
                val r = region.cornerRadius.coerceAtMost(min(b.width(), b.height()) / 2f)
                val topBand = b.top + r
                val bottomBand = b.bottom - r
                val full = b.width() - padding * 2
                when {
                    y < topBand -> {
                        val t = (topBand - y) / r.coerceAtLeast(1f)
                        val chord = r * (1f - sqrt((1f - t).coerceIn(0f, 1f)))
                        (full - 2f * chord).coerceAtLeast(full * 0.35f)
                    }
                    y > bottomBand -> {
                        val t = (y - bottomBand) / r.coerceAtLeast(1f)
                        val chord = r * (1f - sqrt((1f - t).coerceIn(0f, 1f)))
                        (full - 2f * chord).coerceAtLeast(full * 0.35f)
                    }
                    else -> full
                }
            }
            BubbleShape.RECT -> b.width() - padding * 2
        }.coerceAtLeast(0f)
    }

    private fun fallbackRegion(textBox: Rect, pageW: Int, pageH: Int, detected: Boolean): SpeechBubbleRegion {
        val padX = max(4, (textBox.width() * 0.06f).roundToInt())
        val padY = max(4, (textBox.height() * 0.08f).roundToInt())
        val bounds = RectF(
            max(0, textBox.left - padX).toFloat(),
            max(0, textBox.top - padY).toFloat(),
            min(pageW, textBox.right + padX).toFloat(),
            min(pageH, textBox.bottom + padY).toFloat(),
        )
        val corner = min(bounds.width(), bounds.height()) * 0.18f
        return SpeechBubbleRegion(bounds, BubbleShape.ROUNDED_RECT, corner, detected)
    }

    private fun expandSearchBox(textBox: Rect, pageW: Int, pageH: Int): Rect {
        val growX = max(textBox.width() * 1.2f, 48f).roundToInt()
        val growY = max(textBox.height() * 1.2f, 48f).roundToInt()
        return Rect(
            max(0, textBox.left - growX),
            max(0, textBox.top - growY),
            min(pageW, textBox.right + growX),
            min(pageH, textBox.bottom + growY),
        )
    }

    private fun sampleSeedLuminance(bitmap: Bitmap, textBox: Rect): Float {
        val samples = ArrayList<Float>(16)
        val inset = max(1, min(textBox.width(), textBox.height()) / 8)
        val points = listOf(
            textBox.left + inset to textBox.top + inset,
            textBox.right - inset - 1 to textBox.top + inset,
            textBox.left + inset to textBox.bottom - inset - 1,
            textBox.right - inset - 1 to textBox.bottom - inset - 1,
            textBox.centerX() to textBox.top + inset,
            textBox.centerX() to textBox.bottom - inset - 1,
            textBox.left + inset to textBox.centerY(),
            textBox.right - inset - 1 to textBox.centerY(),
        )
        for ((x, y) in points) {
            samples += luminance(
                bitmap,
                x.coerceIn(0, bitmap.width - 1),
                y.coerceIn(0, bitmap.height - 1),
            )
        }
        samples.sort()
        // Prefer bright bubble fill over dark ink.
        return samples[((samples.size - 1) * 0.75f).roundToInt()]
    }

    /**
     * Queue flood-fill. Returns bounding box of fill + pixel count, or null on failure.
     */
    private fun floodFill(
        bitmap: Bitmap,
        textBox: Rect,
        search: Rect,
        seedLum: Float,
    ): IntArray? {
        val w = search.width()
        val h = search.height()
        if (w <= 2 || h <= 2) return null

        val visited = BooleanArray(w * h)
        val queueX = IntArray(w * h)
        val queueY = IntArray(w * h)
        var head = 0
        var tail = 0

        fun idx(x: Int, y: Int) = (y - search.top) * w + (x - search.left)

        fun tryEnqueue(x: Int, y: Int) {
            if (x < search.left || x >= search.right || y < search.top || y >= search.bottom) return
            val i = idx(x, y)
            if (visited[i]) return
            val lum = luminance(bitmap, x, y)
            // Accept fill-like pixels; also mid tones near seed (anti-aliased bubble).
            val tol = if (seedLum >= 0.55f) 0.28f else 0.22f
            if (abs(lum - seedLum) > tol) return
            // Reject strong ink relative to a light bubble.
            if (seedLum >= 0.55f && lum < seedLum - 0.35f) return
            visited[i] = true
            queueX[tail] = x
            queueY[tail] = y
            tail++
        }

        // Seed from several points around the text box edge (bubble fill).
        val seeds = listOf(
            textBox.left + 2 to textBox.centerY(),
            textBox.right - 3 to textBox.centerY(),
            textBox.centerX() to textBox.top + 2,
            textBox.centerX() to textBox.bottom - 3,
            textBox.left + 2 to textBox.top + 2,
            textBox.right - 3 to textBox.top + 2,
            textBox.left + 2 to textBox.bottom - 3,
            textBox.right - 3 to textBox.bottom - 3,
        )
        for ((sx, sy) in seeds) {
            tryEnqueue(
                sx.coerceIn(search.left, search.right - 1),
                sy.coerceIn(search.top, search.bottom - 1),
            )
        }
        if (tail == 0) {
            tryEnqueue(textBox.centerX().coerceIn(search.left, search.right - 1), textBox.centerY().coerceIn(search.top, search.bottom - 1))
        }
        if (tail == 0) return null

        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        var count = 0
        val maxPixels = (w * h * 0.85f).roundToInt()

        while (head < tail && count < maxPixels) {
            val x = queueX[head]
            val y = queueY[head]
            head++
            count++
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            tryEnqueue(x + 1, y)
            tryEnqueue(x - 1, y)
            tryEnqueue(x, y + 1)
            tryEnqueue(x, y - 1)
        }

        if (count < 16) return null
        // Ensure the fill still covers most of the text box (otherwise we bled into panel).
        val cover = Rect(minX, minY, maxX + 1, maxY + 1)
        val inter = Rect()
        if (!inter.setIntersect(cover, textBox)) return null
        val coverRatio = (inter.width() * inter.height()).toFloat() /
            (textBox.width() * textBox.height()).toFloat().coerceAtLeast(1f)
        if (coverRatio < 0.45f) return null

        return intArrayOf(minX, minY, maxX, maxY, count)
    }

    private fun classifyShape(bitmap: Bitmap, bounds: RectF, seedLum: Float): BubbleShape {
        val w = bounds.width().roundToInt().coerceAtLeast(4)
        val h = bounds.height().roundToInt().coerceAtLeast(4)
        var ellipseHits = 0
        var roundHits = 0
        var samples = 0
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val a = bounds.width() / 2f
        val b = bounds.height() / 2f
        val r = min(a, b) * 0.35f

        // Sample a ring near the border.
        for (i in 0 until 36) {
            val ang = (Math.PI * 2 * i / 36).toFloat()
            val ex = cx + a * 0.92f * kotlin.math.cos(ang)
            val ey = cy + b * 0.92f * kotlin.math.sin(ang)
            val x = ex.roundToInt().coerceIn(0, bitmap.width - 1)
            val y = ey.roundToInt().coerceIn(0, bitmap.height - 1)
            val lum = luminance(bitmap, x, y)
            val insideBubble = abs(lum - seedLum) < 0.3f || (seedLum > 0.55f && lum > 0.55f)
            samples++
            // Point on ellipse perimeter should be near fill/outline transition.
            val nx = (ex - cx) / a
            val ny = (ey - cy) / b
            val ellipseDist = hypot(nx, ny)
            if (ellipseDist in 0.85f..1.05f && insideBubble) ellipseHits++

            // Rounded-rect corner test: points near corners of bounds.
        }

        // Fill ratio of ellipse vs bounding rect using a coarse grid.
        var inEllipse = 0
        var inRound = 0
        var fillLike = 0
        val stepX = max(1, w / 14)
        val stepY = max(1, h / 14)
        var yy = bounds.top.toInt()
        while (yy < bounds.bottom) {
            var xx = bounds.left.toInt()
            while (xx < bounds.right) {
                val x = xx.coerceIn(0, bitmap.width - 1)
                val y = yy.coerceIn(0, bitmap.height - 1)
                val lum = luminance(bitmap, x, y)
                val fill = abs(lum - seedLum) < 0.28f || (seedLum > 0.55f && lum > seedLum - 0.2f)
                if (fill) {
                    fillLike++
                    val nx = (x - cx) / a
                    val ny = (y - cy) / b
                    if (nx * nx + ny * ny <= 1.02f) inEllipse++
                    // Rounded rect approx: inside if within rect and outside corner circles correctly
                    val lx = x - bounds.left
                    val ty = y - bounds.top
                    val rx = bounds.right - x
                    val by = bounds.bottom - y
                    val inCornerZone = lx < r || rx < r || ty < r || by < r
                    if (!inCornerZone) {
                        inRound++
                    } else {
                        val ccx = when {
                            lx < r && ty < r -> bounds.left + r
                            rx < r && ty < r -> bounds.right - r
                            lx < r && by < r -> bounds.left + r
                            else -> bounds.right - r
                        }
                        val ccy = when {
                            ty < r -> bounds.top + r
                            else -> bounds.bottom - r
                        }
                        if (hypot(x - ccx, y - ccy) <= r + 1f) inRound++
                    }
                }
                xx += stepX
            }
            yy += stepY
        }

        if (fillLike < 8) return BubbleShape.ROUNDED_RECT
        val ellipseScore = inEllipse.toFloat() / fillLike
        val roundScore = inRound.toFloat() / fillLike
        return when {
            ellipseScore >= 0.88f && ellipseScore >= roundScore + 0.04f -> BubbleShape.ELLIPSE
            roundScore >= 0.82f -> BubbleShape.ROUNDED_RECT
            else -> BubbleShape.ROUNDED_RECT
        }
    }

    private fun luminance(bitmap: Bitmap, x: Int, y: Int): Float {
        val p = bitmap.getPixel(x, y)
        return (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
    }
}
