package eu.kanade.tachiyomi.data.translator.translate

import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorSourceLanguage
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences.TranslatorTargetLanguage
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext

class DeepLTranslator(
    private val preferences: PageTranslatorPreferences,
    private val networkHelper: NetworkHelper,
    private val json: Json,
) : TextTranslator {

    override suspend fun translate(
        texts: List<String>,
        sourceLanguage: TranslatorSourceLanguage,
        targetLanguage: TranslatorTargetLanguage,
    ): List<String> = withIOContext {
        if (texts.isEmpty()) return@withIOContext emptyList()
        val apiKey = preferences.deepLApiKey.get().trim()
        require(apiKey.isNotEmpty()) { "DeepL API key is missing" }

        val baseUrl = when (preferences.deepLApiType.get()) {
            PageTranslatorPreferences.DeepLApiType.FREE -> "https://api-free.deepl.com"
            PageTranslatorPreferences.DeepLApiType.PRO -> "https://api.deepl.com"
        }

        val request = DeepLTranslateRequest(
            text = texts,
            sourceLang = sourceLanguage.toDeepLSourceCode(),
            targetLang = targetLanguage.code.ifEmpty { "EN-US" },
        )

        val body = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())

        val response = networkHelper.client.newCall(
            POST(
                url = "$baseUrl/v2/translate",
                headers = okhttp3.Headers.headersOf(
                    "Authorization",
                    "DeepL-Auth-Key $apiKey",
                    "Content-Type",
                    "application/json",
                ),
                body = body,
            ),
        ).awaitSuccess()

        val parsed = with(json) { response.parseAs<DeepLTranslateResponse>() }
        parsed.translations.map { it.text }
    }

    private fun TranslatorSourceLanguage.toDeepLSourceCode(): String? {
        return when (this) {
            TranslatorSourceLanguage.AUTO,
            TranslatorSourceLanguage.DEFAULT,
            -> null
            else -> code
        }
    }

    @Serializable
    private data class DeepLTranslateRequest(
        val text: List<String>,
        @SerialName("source_lang") val sourceLang: String? = null,
        @SerialName("target_lang") val targetLang: String,
    )

    @Serializable
    private data class DeepLTranslateResponse(
        val translations: List<DeepLTranslation>,
    )

    @Serializable
    private data class DeepLTranslation(
        val text: String,
        @SerialName("detected_source_language") val detectedSourceLanguage: String? = null,
    )
}
