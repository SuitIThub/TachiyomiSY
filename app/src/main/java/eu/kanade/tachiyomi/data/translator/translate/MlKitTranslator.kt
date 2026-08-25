package eu.kanade.tachiyomi.data.translator.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorSourceLanguage
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorTargetLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.concurrent.ConcurrentHashMap

class MlKitTranslator : TextTranslator {

    private val translators = ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val mutex = Mutex()

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: TranslatorSourceLanguage,
        targetLanguage: TranslatorTargetLanguage,
    ): List<String> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()

        val sourceCode = sourceLanguage.toMlKitCode() ?: detectLikelySource(texts)
        val targetCode = targetLanguage.toMlKitCode()
        val translator = getOrCreateTranslator(sourceCode, targetCode)

        texts.map { text ->
            try {
                Tasks.await(translator.translate(text))
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "ML Kit translate failed" }
                text
            }
        }
    }

    private suspend fun getOrCreateTranslator(
        source: String,
        target: String,
    ): com.google.mlkit.nl.translate.Translator = mutex.withLock {
        val key = "$source->$target"
        translators[key]?.let { return it }

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        val translator = Translation.getClient(options)
        val conditions = DownloadConditions.Builder().build()
        Tasks.await(translator.downloadModelIfNeeded(conditions))
        translators[key] = translator
        translator
    }

    private fun detectLikelySource(texts: String): String {
        val sample = texts.take(200)
        return when {
            sample.any { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9faf' } &&
                sample.any { it in '\u3040'..'\u30ff' } -> TranslateLanguage.JAPANESE
            sample.any { it in '\uac00'..'\ud7af' } -> TranslateLanguage.KOREAN
            sample.any { it in '\u4e00'..'\u9fff' } -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    private fun detectLikelySource(texts: List<String>): String =
        detectLikelySource(texts.joinToString("\n"))

    private fun TranslatorSourceLanguage.toMlKitCode(): String? {
        return when (this) {
            TranslatorSourceLanguage.AUTO,
            TranslatorSourceLanguage.DEFAULT,
            -> null
            TranslatorSourceLanguage.JA -> TranslateLanguage.JAPANESE
            TranslatorSourceLanguage.ZH -> TranslateLanguage.CHINESE
            TranslatorSourceLanguage.KO -> TranslateLanguage.KOREAN
            TranslatorSourceLanguage.EN -> TranslateLanguage.ENGLISH
            TranslatorSourceLanguage.DE -> TranslateLanguage.GERMAN
            TranslatorSourceLanguage.FR -> TranslateLanguage.FRENCH
            TranslatorSourceLanguage.ES -> TranslateLanguage.SPANISH
            TranslatorSourceLanguage.PT -> TranslateLanguage.PORTUGUESE
            TranslatorSourceLanguage.RU -> TranslateLanguage.RUSSIAN
            TranslatorSourceLanguage.IT -> TranslateLanguage.ITALIAN
        }
    }

    private fun TranslatorTargetLanguage.toMlKitCode(): String {
        return when (this) {
            TranslatorTargetLanguage.DEFAULT,
            TranslatorTargetLanguage.EN_US,
            TranslatorTargetLanguage.EN_GB,
            -> TranslateLanguage.ENGLISH
            TranslatorTargetLanguage.DE -> TranslateLanguage.GERMAN
            TranslatorTargetLanguage.FR -> TranslateLanguage.FRENCH
            TranslatorTargetLanguage.ES -> TranslateLanguage.SPANISH
            TranslatorTargetLanguage.PT_BR,
            TranslatorTargetLanguage.PT_PT,
            -> TranslateLanguage.PORTUGUESE
            TranslatorTargetLanguage.IT -> TranslateLanguage.ITALIAN
            TranslatorTargetLanguage.JA -> TranslateLanguage.JAPANESE
            TranslatorTargetLanguage.ZH -> TranslateLanguage.CHINESE
            TranslatorTargetLanguage.KO -> TranslateLanguage.KOREAN
            TranslatorTargetLanguage.RU -> TranslateLanguage.RUSSIAN
            TranslatorTargetLanguage.NL -> TranslateLanguage.DUTCH
            TranslatorTargetLanguage.PL -> TranslateLanguage.POLISH
            TranslatorTargetLanguage.TR -> TranslateLanguage.TURKISH
            TranslatorTargetLanguage.UK -> TranslateLanguage.UKRAINIAN
            TranslatorTargetLanguage.ID -> TranslateLanguage.INDONESIAN
        }
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
