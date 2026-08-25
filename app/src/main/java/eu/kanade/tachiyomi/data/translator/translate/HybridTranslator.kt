package eu.kanade.tachiyomi.data.translator.translate

import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorProvider
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorSourceLanguage
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorTargetLanguage
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

class HybridTranslator(
    private val preferences: PageTranslatorPreferences,
    private val deepLTranslator: DeepLTranslator,
    private val mlKitTranslator: MlKitTranslator,
) : TextTranslator {

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: TranslatorSourceLanguage,
        targetLanguage: TranslatorTargetLanguage,
    ): List<String> {
        return when (preferences.provider.get()) {
            TranslatorProvider.ON_DEVICE -> mlKitTranslator.translate(texts, sourceLanguage, targetLanguage)
            TranslatorProvider.DEEPL -> deepLTranslator.translate(texts, sourceLanguage, targetLanguage)
            TranslatorProvider.AUTO -> {
                val hasKey = preferences.deepLApiKey.get().isNotBlank()
                if (hasKey) {
                    try {
                        deepLTranslator.translate(texts, sourceLanguage, targetLanguage)
                    } catch (e: Exception) {
                        logcat(LogPriority.WARN, e) { "DeepL failed, falling back to on-device" }
                        mlKitTranslator.translate(texts, sourceLanguage, targetLanguage)
                    }
                } else {
                    mlKitTranslator.translate(texts, sourceLanguage, targetLanguage)
                }
            }
        }
    }
}
