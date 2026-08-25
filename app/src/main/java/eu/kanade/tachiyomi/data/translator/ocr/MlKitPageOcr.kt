package eu.kanade.tachiyomi.data.translator.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import eu.kanade.tachiyomi.data.translator.OcrTextBlock
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.MergeMode
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorSourceLanguage
import eu.kanade.tachiyomi.data.translator.TextOrientation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MlKitPageOcr {

    private val latin by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val japanese by lazy { TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()) }
    private val chinese by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    private val korean by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    suspend fun recognize(
        bitmap: Bitmap,
        sourceLanguage: TranslatorSourceLanguage,
        mergeMode: MergeMode = MergeMode.CONSERVATIVE,
    ): List<OcrTextBlock> = withContext(Dispatchers.Default) {
        val (work, scale) = upscaledForOcr(bitmap)
        try {
            val image = InputImage.fromBitmap(work, 0)
            val raw = when (sourceLanguage) {
                TranslatorSourceLanguage.AUTO,
                TranslatorSourceLanguage.DEFAULT,
                -> recognizeAuto(image, sourceLanguage, work.height)
                else -> recognizeWith(recognizersFor(sourceLanguage), image, sourceLanguage, work.height)
            }

            val scaled = if (scale == 1f) {
                raw
            } else {
                raw.map { block ->
                    block.copy(boundingBox = scaleRect(block.boundingBox, 1f / scale, bitmap.width, bitmap.height))
                }
            }

            OcrBlockMerger.merge(scaled, bitmap.width, bitmap.height, bitmap, mergeMode)
                .filter { it.text.isNotBlank() }
                .sortedWith(readingOrderComparator())
        } finally {
            if (work !== bitmap && !work.isRecycled) {
                work.recycle()
            }
        }
    }

    /**
     * ML Kit struggles with small Hangul / stylized SFX on typical webtoon widths.
     * Mild upscaling before OCR recovers many missed bubbles without a second model.
     */
    private fun upscaledForOcr(bitmap: Bitmap): Pair<Bitmap, Float> {
        val targetMinWidth = 1600
        if (bitmap.width >= targetMinWidth) return bitmap to 1f
        val scale = (targetMinWidth.toFloat() / bitmap.width).coerceIn(1.25f, 2.25f)
        val w = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        // Guard against extreme memory use on very tall webtoon strips.
        if (w.toLong() * h > 12_000_000L) {
            val capped = kotlin.math.sqrt(12_000_000.0 / (bitmap.width.toDouble() * bitmap.height)).toFloat()
            if (capped <= 1.05f) return bitmap to 1f
            val cw = (bitmap.width * capped).roundToInt().coerceAtLeast(1)
            val ch = (bitmap.height * capped).roundToInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, cw, ch, true) to capped
        }
        return Bitmap.createScaledBitmap(bitmap, w, h, true) to scale
    }

    private fun scaleRect(rect: Rect, factor: Float, maxW: Int, maxH: Int): Rect {
        return Rect(
            (rect.left * factor).roundToInt().coerceIn(0, maxW - 1),
            (rect.top * factor).roundToInt().coerceIn(0, maxH - 1),
            (rect.right * factor).roundToInt().coerceIn(1, maxW),
            (rect.bottom * factor).roundToInt().coerceIn(1, maxH),
        )
    }

    /**
     * Pick a single script recognizer for AUTO. Running CJK models on Latin pages
     * (e.g. Spanish) produces tiny nonsense fragments.
     */
    private fun recognizeAuto(
        image: InputImage,
        sourceLanguage: TranslatorSourceLanguage,
        pageHeight: Int,
    ): List<OcrTextBlock> {
        val candidates = listOf(
            latin to "latin",
            japanese to "japanese",
            chinese to "chinese",
            korean to "korean",
        ).map { (recognizer, name) ->
            val blocks = runRecognizer(recognizer, image, sourceLanguage, pageHeight)
            name to blocks
        }

        val best = candidates.maxByOrNull { (_, blocks) -> scoreResultSet(blocks) }
            ?: return emptyList()

        logcat { "OCR auto selected ${best.first} (score=${scoreResultSet(best.second)}, blocks=${best.second.size})" }
        return best.second
    }

    private fun recognizeWith(
        recognizers: List<TextRecognizer>,
        image: InputImage,
        sourceLanguage: TranslatorSourceLanguage,
        pageHeight: Int,
    ): List<OcrTextBlock> {
        val primary = recognizers.firstOrNull() ?: return emptyList()
        val primaryBlocks = runRecognizer(primary, image, sourceLanguage, pageHeight)
        if (recognizers.size == 1) return primaryBlocks

        val latinBlocks = runRecognizer(latin, image, sourceLanguage, pageHeight)
        return mergeNonOverlappingSupplement(primaryBlocks, latinBlocks)
    }

    private fun runRecognizer(
        recognizer: TextRecognizer,
        image: InputImage,
        sourceLanguage: TranslatorSourceLanguage,
        pageHeight: Int,
    ): List<OcrTextBlock> {
        return try {
            val result = Tasks.await(recognizer.process(image))
            result.textBlocks.flatMap { block ->
                expandBlockToUnits(block, sourceLanguage, pageHeight)
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "OCR recognizer failed" }
            emptyList()
        }
    }

    /**
     * Keep tight ML Kit paragraphs as one unit. Only split when the block looks like
     * multiple bubbles/panels glued together (large gaps or extreme height).
     */
    private fun expandBlockToUnits(
        block: Text.TextBlock,
        sourceLanguage: TranslatorSourceLanguage,
        pageHeight: Int,
    ): List<OcrTextBlock> {
        val box = block.boundingBox ?: return emptyList()
        val orientation = detectOrientation(block, sourceLanguage)
        val lines = block.lines
        if (lines.isEmpty()) {
            val text = assembleText(block, orientation).trim()
            return if (text.isEmpty()) emptyList() else listOf(OcrTextBlock(text, Rect(box), orientation))
        }

        val lineBoxes = lines.mapNotNull { it.boundingBox }
        val shouldSplit = lines.size >= 2 && (
            box.height() > pageHeight * 0.22f ||
                hasLooseLineGaps(lineBoxes) ||
                hasSeparatedColumns(lineBoxes)
            )

        if (!shouldSplit) {
            val text = assembleText(block, orientation).trim()
            if (text.isEmpty()) return emptyList()
            return listOf(OcrTextBlock(text, Rect(box), orientation))
        }

        // Split oversized/loose blocks, then regroup consecutive tight line runs.
        val units = lines.mapNotNull { line ->
            val lb = line.boundingBox ?: return@mapNotNull null
            val text = line.text.replace("\n", " ").trim()
            if (text.isEmpty()) return@mapNotNull null
            OcrTextBlock(text = text, boundingBox = Rect(lb), orientation = orientation)
        }
        return regroupTightRuns(units).ifEmpty {
            val text = assembleText(block, orientation).trim()
            if (text.isEmpty()) emptyList() else listOf(OcrTextBlock(text, Rect(box), orientation))
        }
    }

    private fun hasLooseLineGaps(lineBoxes: List<Rect>): Boolean {
        if (lineBoxes.size < 2) return false
        val ordered = lineBoxes.sortedBy { it.top }
        val heights = ordered.map { it.height().toFloat().coerceAtLeast(1f) }
        val avgH = heights.average().toFloat()
        for (i in 0 until ordered.lastIndex) {
            val gap = ordered[i + 1].top - ordered[i].bottom
            if (gap > avgH * 1.35f) return true
        }
        return false
    }

    private fun hasSeparatedColumns(lineBoxes: List<Rect>): Boolean {
        if (lineBoxes.size < 2) return false
        val centers = lineBoxes.map { it.centerX() }
        val spread = (centers.maxOrNull() ?: 0) - (centers.minOrNull() ?: 0)
        val avgW = lineBoxes.map { it.width() }.average().toFloat().coerceAtLeast(1f)
        return spread > avgW * 1.8f
    }

    /**
     * After a forced split, join consecutive stacked lines that clearly belong together.
     */
    private fun regroupTightRuns(units: List<OcrTextBlock>): List<OcrTextBlock> {
        if (units.size <= 1) return units
        val ordered = units.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))
        val out = ArrayList<OcrTextBlock>()
        var current = ordered.first()
        for (i in 1 until ordered.size) {
            val next = ordered[i]
            if (areTightParagraphNeighbors(current, next)) {
                current = joinBlocks(current, next)
            } else {
                out += current
                current = next
            }
        }
        out += current
        return out
    }

    private fun areTightParagraphNeighbors(a: OcrTextBlock, b: OcrTextBlock): Boolean {
        if (a.orientation != b.orientation) return false
        val ra = a.boundingBox
        val rb = b.boundingBox
        val minH = minOf(ra.height(), rb.height()).toFloat().coerceAtLeast(1f)
        val gapY = when {
            ra.bottom < rb.top -> rb.top - ra.bottom
            rb.bottom < ra.top -> ra.top - rb.bottom
            else -> 0
        }
        if (gapY > minH * 1.15f) return false
        val overlap = minOf(ra.right, rb.right) - maxOf(ra.left, rb.left)
        val xOverlap = if (overlap <= 0) {
            0f
        } else {
            overlap.toFloat() / minOf(ra.width(), rb.width()).coerceAtLeast(1)
        }
        val centersClose = abs(ra.centerX() - rb.centerX()) <= max(minH * 1.2f, 28f)
        return xOverlap >= 0.28f || centersClose
    }

    private fun joinBlocks(a: OcrTextBlock, b: OcrTextBlock): OcrTextBlock {
        val topFirst = if (a.boundingBox.top <= b.boundingBox.top) a to b else b to a
        val joiner = if (isMostlyCjkScript(topFirst.first.text + topFirst.second.text)) "" else " "
        val text = listOf(topFirst.first.text, topFirst.second.text)
            .joinToString(joiner) { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
        val union = Rect(a.boundingBox).also { it.union(b.boundingBox) }
        return OcrTextBlock(text, union, a.orientation)
    }

    private fun isMostlyCjkScript(text: String): Boolean {
        val cjk = text.count {
            it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' || it in '\uac00'..'\ud7af'
        }
        val letters = text.count { it.isLetter() }
        return cjk > 0 && cjk >= letters * 0.4f
    }

    private fun scoreResultSet(blocks: List<OcrTextBlock>): Double {
        if (blocks.isEmpty()) return 0.0
        val totalChars = blocks.sumOf { it.text.length }.toDouble()
        val avgLen = totalChars / blocks.size
        val tiny = blocks.count { it.text.replace("\\s".toRegex(), "").length < 2 }
        val scriptBonus = blocks.sumOf { scriptConfidence(it.text) }
        // Prefer real coverage without rewarding mega-merged blobs.
        val sizePenalty = if (blocks.size == 1 && totalChars > 80) 40.0 else 0.0
        return totalChars + avgLen * 6.0 + scriptBonus - tiny * 20.0 - sizePenalty
    }

    private fun scriptConfidence(text: String): Double {
        var latin = 0
        var cjk = 0
        var hangul = 0
        for (c in text) {
            when {
                c in 'A'..'Z' || c in 'a'..'z' || c in 'À'..'ö' || c in 'ø'..'ÿ' -> latin++
                c in '\u3040'..'\u30ff' || c in '\u4e00'..'\u9fff' -> cjk++
                c in '\uac00'..'\ud7af' -> hangul++
            }
        }
        val total = (latin + cjk + hangul).coerceAtLeast(1)
        // Bonus when one script dominates (cleaner recognition).
        val dominant = max(latin, max(cjk, hangul)).toDouble() / total
        return dominant * text.length
    }

    /**
     * Add latin blocks that do not substantially overlap primary CJK detections.
     */
    private fun mergeNonOverlappingSupplement(
        primary: List<OcrTextBlock>,
        supplement: List<OcrTextBlock>,
    ): List<OcrTextBlock> {
        if (supplement.isEmpty()) return primary
        val extras = supplement.filter { extra ->
            primary.none { base ->
                overlapRatio(base.boundingBox, extra.boundingBox) > 0.35f
            }
        }
        return primary + extras
    }

    private fun overlapRatio(a: Rect, b: Rect): Float {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return 0f
        val interArea = inter.width().toFloat() * inter.height()
        val minArea = minOf(a.width() * a.height(), b.width() * b.height()).toFloat().coerceAtLeast(1f)
        return interArea / minArea
    }

    /**
     * Rebuild text from lines using CJK vertical column order when needed.
     */
    private fun assembleText(block: Text.TextBlock, orientation: TextOrientation): String {
        val lines = block.lines
        if (lines.isEmpty()) return block.text

        if (orientation == TextOrientation.HORIZONTAL_LTR) {
            // Prefer line join with spaces for Latin readability.
            val joined = lines.joinToString(" ") { it.text.replace("\n", " ").trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
            return joined.ifBlank { block.text }
        }

        val ordered = when (orientation) {
            TextOrientation.VERTICAL_RTL -> {
                lines.sortedWith(
                    compareByDescending<Text.Line> { it.boundingBox?.centerX() ?: 0 }
                        .thenBy { it.boundingBox?.top ?: 0 },
                )
            }
            TextOrientation.VERTICAL_LTR -> {
                lines.sortedWith(
                    compareBy<Text.Line> { it.boundingBox?.centerX() ?: 0 }
                        .thenBy { it.boundingBox?.top ?: 0 },
                )
            }
            else -> lines
        }

        return ordered.joinToString("") { line ->
            line.text.replace("\n", "").trim()
        }.ifBlank { block.text }
    }

    private fun detectOrientation(
        block: Text.TextBlock,
        sourceLanguage: TranslatorSourceLanguage,
    ): TextOrientation {
        val sample = block.text
        val hangul = sample.count { it in '\uac00'..'\ud7af' }
        val latin = sample.count {
            it in 'A'..'Z' || it in 'a'..'z' || it in 'À'..'ÿ'
        }
        val cjk = sample.count {
            it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff'
        }

        // Webtoon/manhwa Hangul and Latin are effectively always horizontal.
        if (sourceLanguage == TranslatorSourceLanguage.KO || hangul > cjk) {
            return TextOrientation.HORIZONTAL_LTR
        }
        if (latin > hangul + cjk) {
            return TextOrientation.HORIZONTAL_LTR
        }

        val box = block.boundingBox ?: return TextOrientation.HORIZONTAL_LTR
        val width = max(1, box.width())
        val height = max(1, box.height())
        val aspect = height.toFloat() / width.toFloat()

        val lines = block.lines
        val mostlyTallLines = lines.isNotEmpty() && lines.count { line ->
            val lb = line.boundingBox ?: return@count false
            lb.height() > lb.width() * 1.2f
        } >= (lines.size + 1) / 2

        val isVertical = aspect >= VERTICAL_ASPECT_RATIO || mostlyTallLines
        if (!isVertical) return TextOrientation.HORIZONTAL_LTR

        val defaultVertical = when (sourceLanguage) {
            TranslatorSourceLanguage.JA -> TextOrientation.VERTICAL_RTL
            TranslatorSourceLanguage.ZH -> TextOrientation.VERTICAL_LTR
            TranslatorSourceLanguage.AUTO,
            TranslatorSourceLanguage.DEFAULT,
            -> {
                when {
                    sample.any { it in '\u3040'..'\u30ff' } -> TextOrientation.VERTICAL_RTL
                    sample.any { it in '\u4e00'..'\u9fff' } -> TextOrientation.VERTICAL_LTR
                    else -> TextOrientation.VERTICAL_RTL
                }
            }
            else -> TextOrientation.VERTICAL_RTL
        }

        if (lines.size >= 2) {
            val centers = lines.mapNotNull { it.boundingBox?.centerX() }
            if (centers.size >= 2) {
                val spread = (centers.maxOrNull() ?: 0) - (centers.minOrNull() ?: 0)
                if (spread > width * 0.25f) {
                    val firstCenter = lines.first().boundingBox?.centerX() ?: return defaultVertical
                    val avgCenter = centers.average()
                    return if (firstCenter >= avgCenter) {
                        TextOrientation.VERTICAL_RTL
                    } else {
                        TextOrientation.VERTICAL_LTR
                    }
                }
            }
        }

        return defaultVertical
    }

    private fun readingOrderComparator(): Comparator<OcrTextBlock> {
        return Comparator { a, b ->
            when {
                a.orientation == TextOrientation.VERTICAL_RTL ||
                    b.orientation == TextOrientation.VERTICAL_RTL -> {
                    val x = b.boundingBox.centerX().compareTo(a.boundingBox.centerX())
                    if (x != 0 && abs(a.boundingBox.centerX() - b.boundingBox.centerX()) > 20) {
                        x
                    } else {
                        a.boundingBox.top.compareTo(b.boundingBox.top)
                    }
                }
                else -> {
                    val y = a.boundingBox.top.compareTo(b.boundingBox.top)
                    if (y != 0 && abs(a.boundingBox.top - b.boundingBox.top) > 20) {
                        y
                    } else {
                        a.boundingBox.left.compareTo(b.boundingBox.left)
                    }
                }
            }
        }
    }

    private fun recognizersFor(sourceLanguage: TranslatorSourceLanguage): List<TextRecognizer> {
        return when (sourceLanguage) {
            TranslatorSourceLanguage.JA -> listOf(japanese, latin)
            TranslatorSourceLanguage.ZH -> listOf(chinese, latin)
            TranslatorSourceLanguage.KO -> listOf(korean, latin)
            TranslatorSourceLanguage.EN,
            TranslatorSourceLanguage.DE,
            TranslatorSourceLanguage.FR,
            TranslatorSourceLanguage.ES,
            TranslatorSourceLanguage.PT,
            TranslatorSourceLanguage.RU,
            TranslatorSourceLanguage.IT,
            -> listOf(latin)
            TranslatorSourceLanguage.AUTO,
            TranslatorSourceLanguage.DEFAULT,
            -> listOf(latin) // unused; AUTO uses recognizeAuto
        }
    }

    fun close() {
        runCatching { latin.close() }
        runCatching { japanese.close() }
        runCatching { chinese.close() }
        runCatching { korean.close() }
    }

    companion object {
        private const val VERTICAL_ASPECT_RATIO = 1.35f
    }
}
