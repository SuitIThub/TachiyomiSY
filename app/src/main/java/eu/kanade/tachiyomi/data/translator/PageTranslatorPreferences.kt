package eu.kanade.tachiyomi.data.translator

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

class PageTranslatorPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val enabled: Preference<Boolean> = preferenceStore.getBoolean("pref_page_translator_enabled", false)

    val provider: Preference<TranslatorProvider> = preferenceStore.getEnum(
        "pref_page_translator_provider",
        TranslatorProvider.AUTO,
    )

    val deepLApiKey: Preference<String> = preferenceStore.getString(
        Preference.privateKey("pref_page_translator_deepl_api_key"),
        "",
    )

    val deepLApiType: Preference<DeepLApiType> = preferenceStore.getEnum(
        "pref_page_translator_deepl_api_type",
        DeepLApiType.FREE,
    )

    val sourceLanguage: Preference<TranslatorSourceLanguage> = preferenceStore.getEnum(
        "pref_page_translator_source_lang",
        TranslatorSourceLanguage.AUTO,
    )

    val targetLanguage: Preference<TranslatorTargetLanguage> = preferenceStore.getEnum(
        "pref_page_translator_target_lang",
        TranslatorTargetLanguage.EN_US,
    )

    val preloadSize: Preference<Int> = preferenceStore.getInt("pref_page_translator_preload", 4)

    val cacheSize: Preference<Int> = preferenceStore.getInt("pref_page_translator_cache_size", 100)

    val mergeMode: Preference<MergeMode> = preferenceStore.getEnum(
        "pref_page_translator_merge_mode",
        MergeMode.STANDARD,
    )

    fun mangaMode(mangaId: Long): Preference<MangaTranslatorMode> = preferenceStore.getEnum(
        "pref_page_translator_manga_mode_$mangaId",
        MangaTranslatorMode.DEFAULT,
    )

    fun mangaSourceLanguage(mangaId: Long): Preference<TranslatorSourceLanguage> = preferenceStore.getEnum(
        "pref_page_translator_manga_source_$mangaId",
        TranslatorSourceLanguage.DEFAULT,
    )

    fun mangaTargetLanguage(mangaId: Long): Preference<TranslatorTargetLanguage> = preferenceStore.getEnum(
        "pref_page_translator_manga_target_$mangaId",
        TranslatorTargetLanguage.DEFAULT,
    )

    fun isEnabledForManga(mangaId: Long): Boolean {
        return when (mangaMode(mangaId).get()) {
            MangaTranslatorMode.DEFAULT -> enabled.get()
            MangaTranslatorMode.ON -> true
            MangaTranslatorMode.OFF -> false
        }
    }

    fun resolvedSourceLanguage(mangaId: Long): TranslatorSourceLanguage {
        val override = mangaSourceLanguage(mangaId).get()
        return if (override == TranslatorSourceLanguage.DEFAULT) sourceLanguage.get() else override
    }

    /**
     * Resolves source language with optional metadata inference when set to Auto.
     */
    fun resolvedSourceLanguage(
        mangaId: Long,
        inferred: TranslatorSourceLanguage?,
    ): TranslatorSourceLanguage {
        val override = mangaSourceLanguage(mangaId).get()
        if (override != TranslatorSourceLanguage.DEFAULT) return override
        val global = sourceLanguage.get()
        if (global != TranslatorSourceLanguage.AUTO) return global
        return inferred ?: TranslatorSourceLanguage.AUTO
    }

    fun resolvedTargetLanguage(mangaId: Long): TranslatorTargetLanguage {
        val override = mangaTargetLanguage(mangaId).get()
        return if (override == TranslatorTargetLanguage.DEFAULT) targetLanguage.get() else override
    }

    fun toggleForManga(mangaId: Long) {
        if (isEnabledForManga(mangaId)) {
            mangaMode(mangaId).set(MangaTranslatorMode.OFF)
        } else {
            mangaMode(mangaId).set(MangaTranslatorMode.ON)
        }
    }

    enum class TranslatorProvider {
        AUTO,
        ON_DEVICE,
        DEEPL,
    }

    enum class MergeMode {
        /** Prefer separate bubbles/UI labels; only merge nearly touching same-size lines. */
        CONSERVATIVE,

        /** Balanced paragraph merging inside the same bubble. */
        STANDARD,

        /** More aggressive joining of nearby fragments (can over-merge). */
        AGGRESSIVE,
    }

    enum class DeepLApiType {
        FREE,
        PRO,
    }

    enum class MangaTranslatorMode {
        DEFAULT,
        ON,
        OFF,
    }

    enum class TranslatorSourceLanguage(val code: String?) {
        DEFAULT(null),
        AUTO(null),
        JA("JA"),
        ZH("ZH"),
        KO("KO"),
        EN("EN"),
        DE("DE"),
        FR("FR"),
        ES("ES"),
        PT("PT"),
        RU("RU"),
        IT("IT"),
    }

    enum class TranslatorTargetLanguage(val code: String) {
        DEFAULT(""),
        EN_US("EN-US"),
        EN_GB("EN-GB"),
        DE("DE"),
        FR("FR"),
        ES("ES"),
        PT_BR("PT-BR"),
        PT_PT("PT-PT"),
        IT("IT"),
        JA("JA"),
        ZH("ZH"),
        KO("KO"),
        RU("RU"),
        NL("NL"),
        PL("PL"),
        TR("TR"),
        UK("UK"),
        ID("ID"),
    }
}
