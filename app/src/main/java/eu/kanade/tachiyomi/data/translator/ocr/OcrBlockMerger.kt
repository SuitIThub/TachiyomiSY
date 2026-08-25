package eu.kanade.tachiyomi.data.translator.ocr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import eu.kanade.tachiyomi.data.translator.OcrTextBlock
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.MergeMode
import eu.kanade.tachiyomi.data.translator.TextOrientation
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Merges OCR fragments that belong to the same speech bubble / paragraph.
 * Distinct bubbles and far-apart UI labels stay separate.
 */
object OcrBlockMerger {

    fun merge(
        blocks: List<OcrTextBlock>,
        pageWidth: Int,
        pageHeight: Int,
        bitmap: Bitmap? = null,
        mode: MergeMode = MergeMode.STANDARD,
    ): List<OcrTextBlock> {
        if (blocks.size <= 1) return blocks

        val limits = Limits.forMode(mode)
        val working = blocks
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            .toMutableList()

        var changed = true
        var passes = 0
        while (changed && passes < limits.maxPasses) {
            changed = false
            passes++
            var i = 0
            while (i < working.size) {
                var j = i + 1
                while (j < working.size) {
                    val a = working[i]
                    val b = working[j]
                    if (shouldMerge(a, b, pageWidth, pageHeight, bitmap, limits)) {
                        working[i] = mergePair(a, b)
                        working.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }

        // Second pass: stack centered paragraph lines even when widths differ a lot.
        return mergeCenteredStacks(working, pageWidth, pageHeight, bitmap, limits)
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
    }

    private data class Limits(
        val maxPasses: Int,
        val maxHeightRatio: Float,
        val maxCenterDistFactor: Float,
        val stackedGapFactor: Float,
        val stackedOverlap: Float,
        val inlineGapFactor: Float,
        val inlineOverlap: Float,
        val maxUnionHeightFactor: Float,
        val maxUnionAreaFactor: Float,
        val allowBackgroundMix: Boolean,
        val bgLumDelta: Float,
        val centerStackOverlap: Float,
        val centerStackGapFactor: Float,
    ) {
        companion object {
            fun forMode(mode: MergeMode): Limits = when (mode) {
                MergeMode.CONSERVATIVE -> Limits(
                    maxPasses = 5,
                    maxHeightRatio = 1.7f,
                    maxCenterDistFactor = 0.16f,
                    stackedGapFactor = 0.75f,
                    stackedOverlap = 0.35f,
                    inlineGapFactor = 1.0f,
                    inlineOverlap = 0.50f,
                    maxUnionHeightFactor = 0.22f,
                    maxUnionAreaFactor = 0.12f,
                    allowBackgroundMix = false,
                    bgLumDelta = 0.28f,
                    centerStackOverlap = 0.18f,
                    centerStackGapFactor = 1.1f,
                )
                MergeMode.STANDARD -> Limits(
                    maxPasses = 8,
                    maxHeightRatio = 2.2f,
                    maxCenterDistFactor = 0.22f,
                    stackedGapFactor = 1.25f,
                    stackedOverlap = 0.22f,
                    inlineGapFactor = 1.6f,
                    inlineOverlap = 0.35f,
                    maxUnionHeightFactor = 0.36f,
                    maxUnionAreaFactor = 0.20f,
                    allowBackgroundMix = false,
                    bgLumDelta = 0.34f,
                    centerStackOverlap = 0.10f,
                    centerStackGapFactor = 1.45f,
                )
                MergeMode.AGGRESSIVE -> Limits(
                    maxPasses = 10,
                    maxHeightRatio = 2.8f,
                    maxCenterDistFactor = 0.28f,
                    stackedGapFactor = 1.8f,
                    stackedOverlap = 0.12f,
                    inlineGapFactor = 2.2f,
                    inlineOverlap = 0.25f,
                    maxUnionHeightFactor = 0.45f,
                    maxUnionAreaFactor = 0.28f,
                    allowBackgroundMix = true,
                    bgLumDelta = 0.45f,
                    centerStackOverlap = 0.05f,
                    centerStackGapFactor = 1.8f,
                )
            }
        }
    }

    private fun mergeCenteredStacks(
        blocks: List<OcrTextBlock>,
        pageWidth: Int,
        pageHeight: Int,
        bitmap: Bitmap?,
        limits: Limits,
    ): List<OcrTextBlock> {
        if (blocks.size <= 1) return blocks
        val working = blocks
            .sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
            .toMutableList()

        var changed = true
        var guard = 0
        while (changed && guard < 12) {
            changed = false
            guard++
            var i = 0
            while (i < working.size) {
                var j = i + 1
                while (j < working.size) {
                    if (shouldMergeCenteredStack(working[i], working[j], pageWidth, pageHeight, bitmap, limits)) {
                        working[i] = mergePair(working[i], working[j])
                        working.removeAt(j)
                        changed = true
                    } else {
                        j++
                    }
                }
                i++
            }
        }
        return working
    }

    private fun shouldMergeCenteredStack(
        a: OcrTextBlock,
        b: OcrTextBlock,
        pageWidth: Int,
        pageHeight: Int,
        bitmap: Bitmap?,
        limits: Limits,
    ): Boolean {
        if (a.orientation != TextOrientation.HORIZONTAL_LTR) return false
        if (b.orientation != TextOrientation.HORIZONTAL_LTR) return false

        val ra = a.boundingBox
        val rb = b.boundingBox
        val minH = min(ra.height(), rb.height()).toFloat().coerceAtLeast(1f)
        val maxH = max(ra.height(), rb.height()).toFloat()
        if (maxH / minH > limits.maxHeightRatio) return false

        val gapY = verticalGap(ra, rb)
        if (gapY > minH * limits.centerStackGapFactor) return false

        val union = Rect(ra).also { it.union(rb) }
        if (union.height() > pageHeight * limits.maxUnionHeightFactor) return false
        if (union.width() * union.height() > pageWidth * pageHeight * limits.maxUnionAreaFactor) return false

        val centersClose = abs(ra.centerX() - rb.centerX()) <= max(minH * 1.8f, min(ra.width(), rb.width()) * 0.55f)
        val xOverlap = horizontalOverlapRatio(ra, rb)
        if (!centersClose && xOverlap < limits.centerStackOverlap) return false

        // Don't bridge across a wide panel gutter (side-by-side panels).
        if (gapXAcrossGutter(ra, rb, pageWidth)) return false

        if (bitmap != null && !limits.allowBackgroundMix && backgroundsDiffer(bitmap, ra, rb, limits.bgLumDelta)) {
            return false
        }
        return true
    }

    private fun gapXAcrossGutter(a: Rect, b: Rect, pageWidth: Int): Boolean {
        // Two boxes in left/right halves with a clear horizontal separation.
        val gapX = horizontalGap(a, b)
        if (gapX < pageWidth * 0.04f) return false
        val left = if (a.centerX() <= b.centerX()) a else b
        val right = if (a.centerX() <= b.centerX()) b else a
        return left.centerX() < pageWidth * 0.48f &&
            right.centerX() > pageWidth * 0.52f &&
            gapX > pageWidth * 0.06f
    }

    private fun shouldMerge(
        a: OcrTextBlock,
        b: OcrTextBlock,
        pageWidth: Int,
        pageHeight: Int,
        bitmap: Bitmap?,
        limits: Limits,
    ): Boolean {
        if (a.orientation != b.orientation) return false

        val ra = a.boundingBox
        val rb = b.boundingBox
        val gapX = horizontalGap(ra, rb)
        val gapY = verticalGap(ra, rb)
        val minH = min(ra.height(), rb.height()).toFloat().coerceAtLeast(1f)
        val maxH = max(ra.height(), rb.height()).toFloat()
        val minW = min(ra.width(), rb.width()).toFloat().coerceAtLeast(1f)
        val avgH = (ra.height() + rb.height()) / 2f
        val avgW = (ra.width() + rb.width()) / 2f
        val pageDiag = max(pageWidth, pageHeight).toFloat()

        if (maxH / minH > limits.maxHeightRatio) return false
        if (centerDistance(ra, rb) > pageDiag * limits.maxCenterDistFactor) return false
        if (gapY > minH * (limits.stackedGapFactor + 0.35f) && gapX > minW * 0.45f) return false

        val union = Rect(ra).also { it.union(rb) }
        if (union.height() > pageHeight * limits.maxUnionHeightFactor) return false
        if (union.width() * union.height() > pageWidth * pageHeight * limits.maxUnionAreaFactor) return false

        if (gapXAcrossGutter(ra, rb, pageWidth)) return false

        if (bitmap != null && !limits.allowBackgroundMix && backgroundsDiffer(bitmap, ra, rb, limits.bgLumDelta)) {
            return false
        }

        return when (a.orientation) {
            TextOrientation.HORIZONTAL_LTR -> {
                val xOverlap = horizontalOverlapRatio(ra, rb)
                val yOverlap = verticalOverlapRatio(ra, rb)
                val centersClose = abs(ra.centerX() - rb.centerX()) <= max(avgH * 1.5f, 32f)

                val stacked = gapY <= minH * limits.stackedGapFactor &&
                    (xOverlap >= limits.stackedOverlap || centersClose) &&
                    gapX <= avgW * 0.55f

                val inline = gapX <= avgH * limits.inlineGapFactor &&
                    yOverlap >= limits.inlineOverlap &&
                    gapY <= minH * 0.55f &&
                    abs(ra.centerY() - rb.centerY()) <= minH * 0.55f

                val touching = Rect.intersects(ra, rb) ||
                    (gapX <= 4 && gapY <= 4 && xOverlap >= 0.30f)

                stacked || inline || touching
            }
            TextOrientation.VERTICAL_RTL,
            TextOrientation.VERTICAL_LTR,
            -> {
                val xOverlap = horizontalOverlapRatio(ra, rb)
                val yOverlap = verticalOverlapRatio(ra, rb)
                val sameColumn = gapY <= minW * limits.stackedGapFactor && xOverlap >= limits.stackedOverlap
                val adjacentColumn = gapX <= minW * limits.stackedGapFactor &&
                    yOverlap >= limits.stackedOverlap
                sameColumn || adjacentColumn || Rect.intersects(ra, rb)
            }
        }
    }

    private fun backgroundsDiffer(bitmap: Bitmap, a: Rect, b: Rect, delta: Float): Boolean {
        val lumA = sampleLocalLuminance(bitmap, a)
        val lumB = sampleLocalLuminance(bitmap, b)
        if (abs(lumA - lumB) > delta) return true
        // Only treat as different "theme" when clearly light vs dark paper.
        return (lumA >= 0.62f) != (lumB >= 0.62f) && abs(lumA - lumB) > delta * 0.55f
    }

    private fun sampleLocalLuminance(bitmap: Bitmap, rect: Rect): Float {
        val samples = ArrayList<Float>(12)
        val inset = max(1, min(rect.width(), rect.height()) / 6)
        val points = listOf(
            rect.left + inset to rect.top + inset,
            rect.right - inset - 1 to rect.top + inset,
            rect.left + inset to rect.bottom - inset - 1,
            rect.right - inset - 1 to rect.bottom - inset - 1,
            rect.centerX() to rect.top + inset,
            rect.centerX() to rect.bottom - inset - 1,
        )
        for ((x, y) in points) {
            val px = x.coerceIn(0, bitmap.width - 1)
            val py = y.coerceIn(0, bitmap.height - 1)
            val p = bitmap.getPixel(px, py)
            samples += (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
        }
        samples.sort()
        // Prefer brighter samples (bubble paper) over ink.
        return samples[((samples.size - 1) * 0.8f).toInt()]
    }

    private fun mergePair(a: OcrTextBlock, b: OcrTextBlock): OcrTextBlock {
        val union = Rect(a.boundingBox).also { it.union(b.boundingBox) }
        val orientation = a.orientation
        val ordered = orderForJoin(a, b, orientation)
        val joiner = if (isMostlyCjk(ordered.first.text + ordered.second.text)) "" else " "
        val text = listOf(ordered.first.text, ordered.second.text)
            .joinToString(joiner) { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()

        return OcrTextBlock(text = text, boundingBox = union, orientation = orientation)
    }

    private fun orderForJoin(
        a: OcrTextBlock,
        b: OcrTextBlock,
        orientation: TextOrientation,
    ): Pair<OcrTextBlock, OcrTextBlock> {
        return when (orientation) {
            TextOrientation.VERTICAL_RTL -> {
                when {
                    abs(a.boundingBox.centerX() - b.boundingBox.centerX()) > 12 ->
                        if (a.boundingBox.centerX() >= b.boundingBox.centerX()) a to b else b to a
                    else ->
                        if (a.boundingBox.top <= b.boundingBox.top) a to b else b to a
                }
            }
            TextOrientation.VERTICAL_LTR -> {
                when {
                    abs(a.boundingBox.centerX() - b.boundingBox.centerX()) > 12 ->
                        if (a.boundingBox.centerX() <= b.boundingBox.centerX()) a to b else b to a
                    else ->
                        if (a.boundingBox.top <= b.boundingBox.top) a to b else b to a
                }
            }
            TextOrientation.HORIZONTAL_LTR -> {
                when {
                    abs(a.boundingBox.top - b.boundingBox.top) > 12 ->
                        if (a.boundingBox.top <= b.boundingBox.top) a to b else b to a
                    else ->
                        if (a.boundingBox.left <= b.boundingBox.left) a to b else b to a
                }
            }
        }
    }

    private fun isMostlyCjk(text: String): Boolean {
        val cjk = text.count {
            it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' || it in '\uac00'..'\ud7af'
        }
        val letters = text.count { it.isLetter() }
        return cjk > 0 && cjk >= letters * 0.4f
    }

    private fun horizontalGap(a: Rect, b: Rect): Int = when {
        a.right < b.left -> b.left - a.right
        b.right < a.left -> a.left - b.right
        else -> 0
    }

    private fun verticalGap(a: Rect, b: Rect): Int = when {
        a.bottom < b.top -> b.top - a.bottom
        b.bottom < a.top -> a.top - b.bottom
        else -> 0
    }

    private fun horizontalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = min(a.right, b.right) - max(a.left, b.left)
        if (overlap <= 0) return 0f
        return overlap.toFloat() / min(a.width(), b.width()).coerceAtLeast(1)
    }

    private fun verticalOverlapRatio(a: Rect, b: Rect): Float {
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlap <= 0) return 0f
        return overlap.toFloat() / min(a.height(), b.height()).coerceAtLeast(1)
    }

    private fun centerDistance(a: Rect, b: Rect): Float {
        val dx = (a.centerX() - b.centerX()).toFloat()
        val dy = (a.centerY() - b.centerY()).toFloat()
        return sqrt(dx * dx + dy * dy)
    }
}
