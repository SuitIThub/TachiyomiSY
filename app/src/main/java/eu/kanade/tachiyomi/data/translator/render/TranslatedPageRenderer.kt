package eu.kanade.tachiyomi.data.translator.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import eu.kanade.tachiyomi.data.translator.TextOrientation
import eu.kanade.tachiyomi.data.translator.TranslatedTextBlock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class TranslatedPageRenderer(
    private val bubbleDetector: SpeechBubbleDetector = SpeechBubbleDetector(),
) {

    fun render(bitmap: Bitmap, blocks: List<TranslatedTextBlock>): Bitmap {
        val mutable = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }
        val canvas = Canvas(mutable)
        val validBlocks = blocks.filter { it.translated.isNotBlank() }
        if (validBlocks.isEmpty()) return mutable

        val prepared = validBlocks.map { block ->
            val layoutRegion = tightRegion(mutable, block)
            val (backgroundColor, textColor) = chooseColors(mutable, block.boundingBox, layoutRegion.bounds)
            // Cover at least OCR; when a bubble is found, cover its clamped interior too.
            val cover = Rect(padRect(block.boundingBox, mutable.width, mutable.height, 0.05f)).also { c ->
                if (layoutRegion.detected) {
                    c.union(rectFrom(layoutRegion.bounds))
                }
            }

            // First pass: fit text into the layout region at the largest readable size.
            var layout = layoutText(
                text = block.translated,
                region = layoutRegion,
                textColor = textColor,
                verticalSource = block.orientation != TextOrientation.HORIZONTAL_LTR,
            )

            // Overlay must hide original glyphs; grow with content after large-font layout.
            var overlay = RectF(cover).also { it.union(layout.contentBounds) }

            // If OCR/bubble cover is much larger than the text, re-fit using the full overlay
            // so short translations (e.g. names) fill the available space instead of staying tiny.
            if (overlay.height() > layout.contentBounds.height() * 1.25f ||
                overlay.width() > layout.contentBounds.width() * 1.25f
            ) {
                layout = layoutText(
                    text = block.translated,
                    region = layoutRegion.copy(bounds = RectF(overlay)),
                    textColor = textColor,
                    verticalSource = block.orientation != TextOrientation.HORIZONTAL_LTR,
                )
                overlay = RectF(cover).also { it.union(layout.contentBounds) }
            }

            PreparedOverlay(
                block = block,
                layoutRegion = layoutRegion,
                overlay = overlay,
                cover = cover,
                backgroundColor = backgroundColor,
                textColor = textColor,
                layout = layout,
            )
        }

        val resolvedBounds = resolveOverlayCollisions(
            desired = prepared.map { rectFrom(it.overlay) },
            originals = validBlocks.map { it.boundingBox },
            pageWidth = mutable.width,
            pageHeight = mutable.height,
        )

        prepared.forEachIndexed { index, item ->
            val resolved = resolvedBounds[index]
            val overlay = RectF(
                resolved.left.toFloat(),
                resolved.top.toFloat(),
                resolved.right.toFloat(),
                resolved.bottom.toFloat(),
            )
            overlay.union(RectF(item.cover))

            coverOriginalInk(
                canvas = canvas,
                bitmap = mutable,
                box = item.cover,
                backgroundColor = item.backgroundColor,
            )

            // Final pass: always size text to the resolved overlay (collision may have changed it).
            val finalLayout = layoutText(
                text = item.block.translated,
                region = item.layoutRegion.copy(bounds = overlay),
                textColor = item.textColor,
                verticalSource = item.block.orientation != TextOrientation.HORIZONTAL_LTR,
            )

            val region = item.layoutRegion.copy(bounds = overlay)
            val path = bubbleDetector.toPath(region)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = item.backgroundColor
                style = Paint.Style.FILL
            }
            canvas.drawPath(path, bgPaint)

            canvas.save()
            canvas.clipPath(path)
            drawLaidOutText(canvas, finalLayout, overlay)
            canvas.restore()
        }

        return mutable
    }

    /**
     * Prefer detected speech-bubble geometry but clamp so short translations don't sit
     * in huge empty overlays. Always at least cover the OCR box.
     */
    private fun tightRegion(bitmap: Bitmap, block: TranslatedTextBlock): SpeechBubbleRegion {
        val ocr = block.boundingBox
        val cover = padRect(ocr, bitmap.width, bitmap.height, 0.04f)
        val detected = bubbleDetector.detect(bitmap, ocr)

        val candidate = if (detected.detected) {
            clampBubbleToText(detected.bounds, ocr, bitmap.width, bitmap.height)
        } else {
            fallbackBounds(block, bitmap.width, bitmap.height)
        }

        // Layout region must cover OCR; may grow for readable horizontal EN from vertical CJK.
        candidate.union(RectF(cover))
        if (block.orientation != TextOrientation.HORIZONTAL_LTR) {
            val extra = min(candidate.width() * 0.45f, bitmap.width * 0.10f)
            val cx = candidate.centerX()
            candidate.left = (cx - candidate.width() / 2f - extra / 2f).coerceAtLeast(0f)
            candidate.right = (cx + candidate.width() / 2f + extra / 2f).coerceAtMost(bitmap.width.toFloat())
        }

        val corner = min(candidate.width(), candidate.height()) * 0.16f
        return SpeechBubbleRegion(
            bounds = candidate,
            shape = if (detected.detected) detected.shape else BubbleShape.ROUNDED_RECT,
            cornerRadius = if (detected.detected) detected.cornerRadius.coerceAtMost(corner * 1.4f) else corner,
            detected = detected.detected,
        )
    }

    private fun clampBubbleToText(
        bubble: RectF,
        ocr: Rect,
        pageW: Int,
        pageH: Int,
    ): RectF {
        // Allow more room than OCR so English can render larger; still avoid panel-wide fills.
        val maxW = max(ocr.width() * 2.8f, ocr.width() + 56f).coerceAtMost(pageW * 0.72f)
        val maxH = max(ocr.height() * 3.2f, ocr.height() + 64f).coerceAtMost(pageH * 0.45f)
        val out = RectF(bubble)
        if (out.width() > maxW) {
            val cx = out.centerX().coerceIn(ocr.centerX() - maxW / 2f, ocr.centerX() + maxW / 2f)
            out.left = (cx - maxW / 2f).coerceAtLeast(0f)
            out.right = (out.left + maxW).coerceAtMost(pageW.toFloat())
        }
        if (out.height() > maxH) {
            val cy = out.centerY().coerceIn(ocr.centerY() - maxH / 2f, ocr.centerY() + maxH / 2f)
            out.top = (cy - maxH / 2f).coerceAtLeast(0f)
            out.bottom = (out.top + maxH).coerceAtMost(pageH.toFloat())
        }
        return out
    }

    private fun fallbackBounds(block: TranslatedTextBlock, pageW: Int, pageH: Int): RectF {
        val ocr = block.boundingBox
        // Give horizontal translations a usable minimum width so font sizing isn't starved.
        val minW = when {
            block.orientation != TextOrientation.HORIZONTAL_LTR -> ocr.width().toFloat()
            else -> max(ocr.width().toFloat(), min(pageW * 0.28f, max(120f, ocr.height() * 4.5f)))
        }
        val minH = max(ocr.height().toFloat(), min(pageH * 0.06f, 36f))
        val padX = max(4, (ocr.width() * 0.05f).roundToInt())
        val padY = max(4, (ocr.height() * 0.08f).roundToInt())
        val cx = ocr.centerX().toFloat()
        val cy = ocr.centerY().toFloat()
        val width = max(ocr.width() + padX * 2f, minW)
        val height = max(ocr.height() + padY * 2f, minH)
        return RectF(
            (cx - width / 2f).coerceAtLeast(0f),
            (cy - height / 2f).coerceAtLeast(0f),
            (cx + width / 2f).coerceAtMost(pageW.toFloat()),
            (cy + height / 2f).coerceAtMost(pageH.toFloat()),
        )
    }

    /**
     * Opaque fill over a padded OCR box so original glyphs don't ghost through.
     * Semi-transparent overlays previously left Korean/CJK strokes visible.
     */
    private fun coverOriginalInk(
        canvas: Canvas,
        bitmap: Bitmap,
        box: Rect,
        backgroundColor: Int,
    ) {
        // Sample before covering — after the fill, edge luminance is meaningless.
        val bgLum = sampleEdgeLuminance(bitmap, box)
        val inkIsDark = bgLum >= 0.42f

        val cover = padRect(box, bitmap.width, bitmap.height, 0.08f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = backgroundColor
            style = Paint.Style.FILL
        }
        val corner = min(cover.width(), cover.height()) * 0.12f
        canvas.drawRoundRect(RectF(cover), corner, corner, paint)

        // Cover ink that slightly spills past the OCR box (dilated dark/light pixels).
        val dilate = max(2, min(box.width(), box.height()) / 12)
        val scan = Rect(
            max(0, box.left - dilate),
            max(0, box.top - dilate),
            min(bitmap.width, box.right + dilate),
            min(bitmap.height, box.bottom + dilate),
        )
        val threshold = if (inkIsDark) bgLum - 0.16f else bgLum + 0.16f
        val step = if (scan.width() * scan.height() > 40_000) 2 else 1
        var y = scan.top
        while (y < scan.bottom) {
            var x = scan.left
            while (x < scan.right) {
                // Skip interior already filled by the roundrect.
                if (x >= cover.left && x < cover.right && y >= cover.top && y < cover.bottom) {
                    x += step
                    continue
                }
                val lum = pixelLuminance(bitmap, x, y)
                val isInk = if (inkIsDark) lum < threshold else lum > threshold
                if (isInk) {
                    bitmap.setPixel(x, y, backgroundColor)
                    if (step == 1) {
                        if (x + 1 < bitmap.width) bitmap.setPixel(x + 1, y, backgroundColor)
                        if (y + 1 < bitmap.height) bitmap.setPixel(x, y + 1, backgroundColor)
                    }
                }
                x += step
            }
            y += step
        }
    }

    private fun layoutText(
        text: String,
        region: SpeechBubbleRegion,
        textColor: Int,
        @Suppress("UNUSED_PARAMETER") verticalSource: Boolean,
    ): TextLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        val padding = max(4f, min(region.bounds.width(), region.bounds.height()) * 0.06f)
        val maxHeight = (region.bounds.height() - padding * 2).coerceAtLeast(8f)
        val maxWidth = (region.bounds.width() - padding * 2).coerceAtLeast(8f)

        // Binary-search the largest font that still fits — fill the bubble instead of
        // starting small and leaving empty white space.
        val hiCap = min(MAX_FONT, max(maxHeight * 0.92f, maxWidth * 0.55f))
        var lo = MIN_FONT
        var hi = hiCap.coerceAtLeast(MIN_FONT)
        var bestSize = MIN_FONT
        var bestLines: List<String> = emptyList()
        var bestLineHeight = 0f

        repeat(14) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            val lineHeight = paint.fontSpacing * 1.08f
            val lines = breakTextToBubble(text, region, paint, padding, lineHeight)
            val totalH = lines.size * lineHeight
            val widest = lines.maxOfOrNull { paint.measureText(it) } ?: 0f
            val fits = lines.isNotEmpty() &&
                totalH <= maxHeight + 0.5f &&
                widest <= maxWidth + 1f
            if (fits) {
                bestSize = mid
                bestLines = lines
                bestLineHeight = lineHeight
                lo = mid + 0.35f
            } else {
                hi = mid - 0.35f
            }
            if (lo > hi) return@repeat
        }

        // Fallback: force a readable minimum even if it slightly overflows.
        if (bestLines.isEmpty()) {
            paint.textSize = MIN_FONT
            bestLineHeight = paint.fontSpacing * 1.08f
            bestLines = breakTextToBubble(text, region, paint, padding, bestLineHeight)
            bestSize = MIN_FONT
            if (bestLines.isEmpty()) {
                return TextLayout(emptyList(), paint, 0f, RectF(), region.bounds.centerX(), 0f)
            }
        }

        paint.textSize = bestSize
        val totalH = bestLines.size * bestLineHeight
        val startY = region.bounds.centerY() - totalH / 2f + bestLineHeight * 0.78f
        val cx = region.bounds.centerX()

        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        for (line in bestLines) {
            val w = paint.measureText(line)
            minX = min(minX, cx - w / 2f)
            maxX = max(maxX, cx + w / 2f)
        }
        val contentPad = max(5f, bestSize * 0.40f)
        val content = RectF(
            (minX - contentPad).coerceAtLeast(region.bounds.left),
            (startY - bestLineHeight * 0.85f - contentPad * 0.35f).coerceAtLeast(region.bounds.top),
            (maxX + contentPad).coerceAtMost(region.bounds.right),
            (startY + (bestLines.size - 1) * bestLineHeight + bestLineHeight * 0.30f + contentPad * 0.35f)
                .coerceAtMost(region.bounds.bottom),
        )

        return TextLayout(bestLines, paint, bestLineHeight, content, cx, startY)
    }

    private fun drawLaidOutText(canvas: Canvas, layout: TextLayout, overlay: RectF) {
        if (layout.lines.isEmpty()) return
        val paint = layout.paint
        val totalH = layout.lines.size * layout.lineHeight
        var y = overlay.centerY() - totalH / 2f + layout.lineHeight * 0.78f
        val cx = overlay.centerX()
        val baseSize = paint.textSize
        for (line in layout.lines) {
            var size = baseSize
            paint.textSize = size
            val available = (overlay.width() - 10f).coerceAtLeast(8f)
            while (size > MIN_FONT && paint.measureText(line) > available) {
                size -= 0.6f
                paint.textSize = size
            }
            canvas.drawText(line, cx, y, paint)
            paint.textSize = baseSize
            y += layout.lineHeight
        }
    }

    private fun breakTextToBubble(
        text: String,
        region: SpeechBubbleRegion,
        paint: TextPaint,
        padding: Float,
        lineHeight: Float,
    ): List<String> {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val lines = ArrayList<String>()
        val maxLines = max(1, ((region.bounds.height() - padding * 2) / lineHeight).toInt() + 1)
        var y = region.bounds.top + padding + lineHeight * 0.5f
        var current = StringBuilder()

        fun widthBudget(): Float = bubbleDetector.lineWidthAt(region, y, padding).coerceAtLeast(8f)

        for (word in words) {
            val candidate = if (current.isEmpty()) word else "${current} $word"
            if (paint.measureText(candidate) <= widthBudget()) {
                current.clear()
                current.append(candidate)
            } else {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current.clear()
                    y += lineHeight
                    if (lines.size >= maxLines) break
                }
                if (paint.measureText(word) > widthBudget()) {
                    var rest = word
                    while (rest.isNotEmpty() && lines.size < maxLines) {
                        val budget = widthBudget()
                        var cut = rest.length
                        while (cut > 1 && paint.measureText(rest.substring(0, cut)) > budget) {
                            cut--
                        }
                        lines += rest.substring(0, cut)
                        rest = rest.substring(cut)
                        y += lineHeight
                    }
                } else {
                    current.append(word)
                }
            }
        }
        if (current.isNotEmpty() && lines.size < maxLines) {
            lines += current.toString()
        }
        return lines
    }

    private fun resolveOverlayCollisions(
        desired: List<Rect>,
        originals: List<Rect>,
        pageWidth: Int,
        pageHeight: Int,
    ): List<Rect> {
        val result = desired.map { Rect(it) }.toMutableList()
        val order = result.indices.sortedByDescending { result[it].width() * result[it].height() }

        for (pass in 0 until 3) {
            for (i in order) {
                val mine = result[i]
                for (j in originals.indices) {
                    if (i == j) continue
                    if (!Rect.intersects(mine, originals[j])) continue
                    separateFrom(mine, originals[j], pageWidth, pageHeight, keepMin = originals[i])
                }
                for (j in result.indices) {
                    if (i == j) continue
                    if (!Rect.intersects(mine, result[j])) continue
                    separateFrom(mine, result[j], pageWidth, pageHeight, keepMin = originals[i])
                }
                clampToPage(mine, pageWidth, pageHeight)
            }
        }

        for (i in result.indices) {
            val heavy = originals.indices.any { j ->
                i != j && overlapRatio(result[i], originals[j]) > 0.4f
            }
            if (heavy) {
                result[i] = padRect(originals[i], pageWidth, pageHeight, 0.05f)
            }
        }

        for (i in result.indices) {
            for (j in i + 1 until result.size) {
                if (!Rect.intersects(result[i], result[j])) continue
                val smaller = if (area(result[i]) <= area(result[j])) i else j
                result[smaller] = padRect(originals[smaller], pageWidth, pageHeight, 0.05f)
                if (Rect.intersects(result[i], result[j])) {
                    separateFrom(
                        result[smaller],
                        result[if (smaller == i) j else i],
                        pageWidth,
                        pageHeight,
                        originals[smaller],
                    )
                }
            }
        }
        return result
    }

    private fun separateFrom(
        moving: Rect,
        obstacle: Rect,
        pageWidth: Int,
        pageHeight: Int,
        keepMin: Rect,
    ) {
        val inter = Rect()
        if (!inter.setIntersect(moving, obstacle)) return

        val pushLeft = moving.right - obstacle.left
        val pushRight = obstacle.right - moving.left
        val pushUp = moving.bottom - obstacle.top
        val pushDown = obstacle.bottom - moving.top
        val minPush = min(min(pushLeft, pushRight), min(pushUp, pushDown))

        when (minPush) {
            pushLeft -> moving.right = max(moving.left + keepMin.width().coerceAtLeast(8), obstacle.left - GAP)
            pushRight -> moving.left = min(moving.right - keepMin.width().coerceAtLeast(8), obstacle.right + GAP)
            pushUp -> moving.bottom = max(moving.top + keepMin.height().coerceAtLeast(8), obstacle.top - GAP)
            else -> moving.top = min(moving.bottom - keepMin.height().coerceAtLeast(8), obstacle.bottom + GAP)
        }

        if (moving.width() < keepMin.width() * 0.65f || moving.height() < keepMin.height() * 0.65f) {
            moving.set(padRect(keepMin, pageWidth, pageHeight, 0.04f))
        }
        clampToPage(moving, pageWidth, pageHeight)
    }

    private fun clampToPage(rect: Rect, pageWidth: Int, pageHeight: Int) {
        if (rect.left < 2) {
            rect.right += 2 - rect.left
            rect.left = 2
        }
        if (rect.top < 2) {
            rect.bottom += 2 - rect.top
            rect.top = 2
        }
        if (rect.right > pageWidth - 2) {
            rect.left -= rect.right - (pageWidth - 2)
            rect.right = pageWidth - 2
        }
        if (rect.bottom > pageHeight - 2) {
            rect.top -= rect.bottom - (pageHeight - 2)
            rect.bottom = pageHeight - 2
        }
        rect.left = rect.left.coerceIn(0, pageWidth - 2)
        rect.top = rect.top.coerceIn(0, pageHeight - 2)
        rect.right = rect.right.coerceIn(rect.left + 1, pageWidth)
        rect.bottom = rect.bottom.coerceIn(rect.top + 1, pageHeight)
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return 0f
        val interArea = inter.width().toFloat() * inter.height()
        val minArea = min(area(a), area(b)).toFloat().coerceAtLeast(1f)
        return interArea / minArea
    }

    private fun area(r: Rect): Int = r.width().coerceAtLeast(0) * r.height().coerceAtLeast(0)

    private fun rectFrom(r: RectF): Rect =
        Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())

    private fun chooseColors(
        bitmap: Bitmap,
        originalBox: Rect,
        overlayBounds: RectF,
    ): Pair<Int, Int> {
        val edgeLuminance = sampleEdgeLuminance(bitmap, originalBox)
        val overlayEdge = sampleEdgeLuminance(bitmap, rectFrom(overlayBounds))
        val luminance = max(edgeLuminance, overlayEdge)
        return if (luminance >= 0.42f) {
            Color.argb(255, 252, 252, 252) to Color.rgb(18, 18, 18)
        } else {
            Color.argb(255, 22, 22, 22) to Color.rgb(250, 250, 250)
        }
    }

    private fun sampleEdgeLuminance(bitmap: Bitmap, rect: Rect): Float {
        val samples = ArrayList<Float>(48)
        val inset = max(1, min(rect.width(), rect.height()) / 10)
        val left = (rect.left + inset).coerceIn(0, bitmap.width - 1)
        val right = (rect.right - inset - 1).coerceIn(0, bitmap.width - 1)
        val top = (rect.top + inset).coerceIn(0, bitmap.height - 1)
        val bottom = (rect.bottom - inset - 1).coerceIn(0, bitmap.height - 1)
        if (right <= left || bottom <= top) return 0.85f

        val stepX = max(1, (right - left) / 10)
        val stepY = max(1, (bottom - top) / 10)
        var x = left
        while (x <= right) {
            samples += pixelLuminance(bitmap, x, top)
            samples += pixelLuminance(bitmap, x, bottom)
            x += stepX
        }
        var y = top
        while (y <= bottom) {
            samples += pixelLuminance(bitmap, left, y)
            samples += pixelLuminance(bitmap, right, y)
            y += stepY
        }
        if (samples.isEmpty()) return 0.85f
        samples.sort()
        return samples[((samples.size - 1) * 0.70f).roundToInt()]
    }

    private fun pixelLuminance(bitmap: Bitmap, x: Int, y: Int): Float {
        val pixel = bitmap.getPixel(x, y)
        return (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel)) / 255f
    }

    private fun padRect(rect: Rect, maxWidth: Int, maxHeight: Int, paddingRatio: Float): Rect {
        val padX = max(3, (rect.width() * paddingRatio).roundToInt())
        val padY = max(3, (rect.height() * paddingRatio).roundToInt())
        return Rect(
            max(0, rect.left - padX),
            max(0, rect.top - padY),
            min(maxWidth, rect.right + padX),
            min(maxHeight, rect.bottom + padY),
        )
    }

    private data class PreparedOverlay(
        val block: TranslatedTextBlock,
        val layoutRegion: SpeechBubbleRegion,
        val overlay: RectF,
        val cover: Rect,
        val backgroundColor: Int,
        val textColor: Int,
        val layout: TextLayout,
    )

    private data class TextLayout(
        val lines: List<String>,
        val paint: TextPaint,
        val lineHeight: Float,
        val contentBounds: RectF,
        val centerX: Float,
        val startY: Float,
    )

    companion object {
        private const val GAP = 5
        private const val MIN_FONT = 13f
        private const val MAX_FONT = 52f
    }
}
