package eu.kanade.tachiyomi.data.aisearch

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class AiSearchPreferences(
    private val preferenceStore: PreferenceStore,
) {
    val enabled: Preference<Boolean> = preferenceStore.getBoolean("pref_ai_search_enabled", false)

    val openRouterApiKey: Preference<String> = preferenceStore.getString(
        Preference.privateKey("pref_ai_search_openrouter_api_key"),
        "",
    )

    val model: Preference<String> = preferenceStore.getString(
        "pref_ai_search_model",
        DEFAULT_MODEL,
    )

    fun isConfigured(): Boolean = enabled.get() && openRouterApiKey.get().isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "google/gemini-2.0-flash-001"
    }
}
