package eu.kanade.tachiyomi.data.translator

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import eu.kanade.tachiyomi.data.translator.ocr.MlKitPageOcr
import eu.kanade.tachiyomi.data.translator.render.TranslatedPageRenderer
import eu.kanade.tachiyomi.data.translator.translate.HybridTranslator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.ByteArrayOutputStream

class PageTranslatorEngine(
    private val ocr: MlKitPageOcr,
    private val translator: HybridTranslator,
    private val renderer: TranslatedPageRenderer,
    private val cache: PageTranslatorCache,
) {

    suspend fun translatePage(
        imageBytes: ByteArray,
        cacheKey: String,
        sourceLanguage: PageTranslatorPreferences.TranslatorSourceLanguage,
        targetLanguage: PageTranslatorPreferences.TranslatorTargetLanguage,
        mergeMode: PageTranslatorPreferences.MergeMode =
            PageTranslatorPreferences.MergeMode.CONSERVATIVE,
    ): ByteArray = withContext(Dispatchers.Default) {
        cache.getFile(cacheKey)?.takeIf { it.exists() }?.readBytes()?.let { return@withContext it }

        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: error("Unable to decode page image")

        try {
            val ocrBlocks = ocr.recognize(bitmap, sourceLanguage, mergeMode)
            if (ocrBlocks.isEmpty()) {
                cache.put(cacheKey, imageBytes)
                return@withContext imageBytes
            }

            val translatedTexts = translator.translate(
                texts = ocrBlocks.map { it.text },
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
            )

            val translatedBlocks = ocrBlocks.mapIndexed { index, block ->
                TranslatedTextBlock(
                    original = block.text,
                    translated = translatedTexts.getOrElse(index) { block.text },
                    boundingBox = block.boundingBox,
                    orientation = block.orientation,
                )
            }

            val rendered = renderer.render(bitmap, translatedBlocks)
            val output = ByteArrayOutputStream()
            rendered.compress(Bitmap.CompressFormat.JPEG, 92, output)
            val bytes = output.toByteArray()
            if (rendered !== bitmap) {
                rendered.recycle()
            }
            cache.put(cacheKey, bytes)
            bytes
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Page translation failed" }
            throw e
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    fun buildCacheKey(
        imageIdentity: String,
        sourceLanguage: PageTranslatorPreferences.TranslatorSourceLanguage,
        targetLanguage: PageTranslatorPreferences.TranslatorTargetLanguage,
        provider: PageTranslatorPreferences.TranslatorProvider,
        transformKey: String = "",
        mergeMode: PageTranslatorPreferences.MergeMode =
            PageTranslatorPreferences.MergeMode.CONSERVATIVE,
    ): String {
        return listOf(
            imageIdentity,
            sourceLanguage.name,
            targetLanguage.name,
            provider.name,
            mergeMode.name,
            transformKey,
            "v${PageTranslatorCache.RENDER_VERSION}",
        ).joinToString("|")
    }
}
