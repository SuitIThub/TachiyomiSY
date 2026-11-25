package eu.kanade.tachiyomi.data.aisearch

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import exh.eh.EHTags
import exh.nhentai.NHTags
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Turns a natural-language prompt into an EH / NHentai search query via OpenRouter.
 *
 * Intentionally does **not** crawl or index galleries — only rewrites the query so the
 * normal site search can run. Keeps token usage low (small system prompt, short reply).
 */
class AiSearchService(
    private val preferences: AiSearchPreferences,
    private val networkHelper: NetworkHelper,
    private val json: Json,
) {

    suspend fun generateQuery(prompt: String, target: AiSearchTarget): String = withIOContext {
        val trimmed = prompt.trim()
        require(trimmed.isNotEmpty()) { "Prompt is empty" }
        require(preferences.isConfigured()) { "AI search is not configured" }

        val apiKey = preferences.openRouterApiKey.get().trim()
        val model = preferences.model.get().trim().ifBlank { AiSearchPreferences.DEFAULT_MODEL }

        val request = ChatCompletionRequest(
            model = model,
            temperature = 0.2,
            maxTokens = 150,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt(target)),
                ChatMessage(role = "user", content = trimmed),
            ),
        )

        val body = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())

        val response = networkHelper.client.newCall(
            POST(
                url = OPENROUTER_URL,
                headers = okhttp3.Headers.headersOf(
                    "Authorization",
                    "Bearer $apiKey",
                    "Content-Type",
                    "application/json",
                    "HTTP-Referer",
                    "https://github.com/jobobby04/TachiyomiSY",
                    "X-Title",
                    "TachiyomiSY AI Search",
                ),
                body = body,
            ),
        ).awaitSuccess()

        val parsed = with(json) { response.parseAs<ChatCompletionResponse>() }
        val content = parsed.choices.firstOrNull()?.message?.content?.trim().orEmpty()
        sanitizeQuery(content).also {
            require(it.isNotEmpty()) { "Model returned an empty query" }
        }
    }

    private fun systemPrompt(target: AiSearchTarget): String {
        return when (target) {
            AiSearchTarget.EHENTAI -> """
                You convert natural-language gallery search requests into E-Hentai / ExHentai f_search queries.
                Return ONLY the search query string. No quotes around the whole answer, no markdown, no explanation.

                Syntax rules:
                - Tags use namespace:value$ (exact match). Example: female:ahegao$
                - Multi-word tags: namespace:"multi word$"
                - Exclude with leading -: -female:lolicon$
                - OR with leading ~: ~female:yuri$
                - Free-text title keywords are allowed without a namespace
                - Prefer precise tags over vague free text when possible
                - Do not invent fake namespaces

                Available namespaces: ${EHTags.getNamespaces().joinToString(", ")}

                Examples of good output:
                language:english$ female:yuri$ school
                language:chinese$ -male:yaoi$ parody:"touhou project$"
                artist:shinama$ female:netorare$
            """.trimIndent()

            AiSearchTarget.NHENTAI -> """
                You convert natural-language gallery search requests into nhentai.net search queries.
                Return ONLY the search query string. No quotes around the whole answer, no markdown, no explanation.

                Syntax rules:
                - Tags use namespace:value. Example: tag:yuri
                - Multi-word values: namespace:"multi word" or "multi word" for plain tags
                - Exclude with leading -: -tag:lolicon
                - Free-text title keywords are allowed
                - Prefer language:english / language:japanese / language:chinese when language is implied
                - Do not invent fake namespaces

                Available namespaces: ${NHTags.getNamespaces().joinToString(", ")}
                Common categories: category:doujinshi, category:manga, category:artistcg, category:gamecg, category:western, category:non-h, category:imageset, category:cosplay, category:asianporn, category:misc

                Examples of good output:
                language:english tag:yuri school
                language:japanese parody:touhou -tag:lolicon
                artist:shinama language:english
            """.trimIndent()
        }
    }

    private fun sanitizeQuery(raw: String): String {
        var text = raw.trim()
        // Strip accidental markdown fences / surrounding quotes
        text = text.removePrefix("```").removeSuffix("```").trim()
        if ((text.startsWith('"') && text.endsWith('"')) || (text.startsWith('\'') && text.endsWith('\''))) {
            text = text.substring(1, text.lastIndex).trim()
        }
        // Keep only the first line if the model rambling
        text = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
        return text
    }

    @Serializable
    private data class ChatCompletionRequest(
        val model: String,
        val temperature: Double,
        @SerialName("max_tokens") val maxTokens: Int,
        val messages: List<ChatMessage>,
    )

    @Serializable
    private data class ChatMessage(
        val role: String,
        val content: String,
    )

    @Serializable
    private data class ChatCompletionResponse(
        val choices: List<Choice> = emptyList(),
    )

    @Serializable
    private data class Choice(
        val message: ChatMessageResponse? = null,
    )

    @Serializable
    private data class ChatMessageResponse(
        val role: String? = null,
        val content: String? = null,
        @SerialName("reasoning") val reasoning: String? = null,
    )

    companion object {
        private const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
    }
}
