package eu.kanade.tachiyomi.data.translator.translate

import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorSourceLanguage
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorTargetLanguage

interface TextTranslator {
    suspend fun translate(
        texts: List<String>,
        sourceLanguage: TranslatorSourceLanguage,
        targetLanguage: TranslatorTargetLanguage,
    ): List<String>
}
