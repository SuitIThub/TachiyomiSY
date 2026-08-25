package eu.kanade.tachiyomi.data.translator

import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.base.FlatMetadata
import exh.source.getMainSource
import exh.util.MangaType
import exh.util.mangaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.util.Locale

/**
 * Infers OCR/translation source language from manga metadata so Auto mode
 * does not have to guess from the page image alone.
 */
class MangaSourceLanguageInferrer(
    private val sourceManager: SourceManager,
) {

    fun infer(
        manga: Manga,
        flatMetadata: FlatMetadata? = null,
    ): PageTranslatorPreferences.TranslatorSourceLanguage? {
        val langFlag = extractLangFlag(manga, flatMetadata)

        // 1) Explicit language tags in genres (strongest signal for page language).
        fromTags(manga.genre)?.let { return it }

        // 2) Explicit language code from metadata (MangaDex originalLanguage / langFlag).
        fromLangCode(langFlag)?.let { code ->
            if (code.isCjk()) {
                // Scanlations often keep originalLanguage=ja while pages are Latin.
                if (!isLatinDominant(manga.title, manga.description, manga.genre)) {
                    return code
                }
            } else {
                return code
            }
        }

        // 3) Source name hints (e.g. "… (ES)", "Spanish", "Raw").
        fromSourceName(sourceManager.get(manga.source)?.name)?.let { return it }

        // 4) Manga/manhwa/manhua type from tags or source.
        fromMangaType(manga)?.let { typeLang ->
            if (!(typeLang.isCjk() && isLatinDominant(manga.title, manga.description, manga.genre))) {
                return typeLang
            }
        }

        // 5) Script heuristics on title + description.
        fromScript(manga.title, manga.description)?.let { return it }

        return null
    }

    private fun extractLangFlag(manga: Manga, flatMetadata: FlatMetadata?): String? {
        if (flatMetadata == null) return null
        val source = sourceManager.get(manga.source)
        try {
            val metaClass = source?.getMainSource<MetadataSource<*, *>>()?.metaClass
            if (metaClass != null) {
                val raised = flatMetadata.raise(metaClass)
                if (raised is MangaDexSearchMetadata) {
                    return raised.langFlag
                }
            }
        } catch (_: Exception) {
        }
        try {
            return flatMetadata.raise<MangaDexSearchMetadata>().langFlag
        } catch (_: Exception) {
        }
        return parseLangFromExtra(flatMetadata.metadata.extra)
    }

    private fun parseLangFromExtra(extra: String?): String? {
        if (extra.isNullOrBlank()) return null
        return try {
            val obj = Json.parseToJsonElement(extra) as? JsonObject ?: return null
            listOf("langFlag", "originalLanguage", "lang", "language")
                .firstNotNullOfOrNull { key ->
                    (obj[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun fromTags(genres: List<String>?): PageTranslatorPreferences.TranslatorSourceLanguage? {
        if (genres.isNullOrEmpty()) return null
        val joined = genres.joinToString("\n").lowercase(Locale.ROOT)

        data class Rule(
            val lang: PageTranslatorPreferences.TranslatorSourceLanguage,
            val needles: List<String>,
            val typeOnlyNeedles: List<String> = emptyList(),
        )

        val rules = listOf(
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.JA,
                listOf("japanese", "japonés", "japones", "日本語", "raw japanese", "raw jp"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.ZH,
                listOf("chinese", "chino", "中文", "国漫", "manhua"),
                typeOnlyNeedles = listOf("manhua"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.KO,
                listOf("korean", "coreano", "한국어", "manhwa"),
                typeOnlyNeedles = listOf("manhwa"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.ES,
                listOf("spanish", "español", "espanol", "castellano"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.EN,
                listOf("english", "inglés", "ingles"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.FR,
                listOf("french", "français", "francais"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.DE,
                listOf("german", "deutsch"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.PT,
                listOf("portuguese", "português", "portugues", "brasileiro"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.IT,
                listOf("italian", "italiano"),
            ),
            Rule(
                PageTranslatorPreferences.TranslatorSourceLanguage.RU,
                listOf("russian", "русский"),
            ),
        )

        for (rule in rules) {
            if (rule.needles.none { joined.contains(it) }) continue
            val onlyTypeWord = rule.typeOnlyNeedles.isNotEmpty() &&
                rule.typeOnlyNeedles.any { joined.contains(it) } &&
                rule.needles.filterNot { it in rule.typeOnlyNeedles }.none { joined.contains(it) }
            if (!onlyTypeWord) return rule.lang
        }
        return null
    }

    private fun fromLangCode(code: String?): PageTranslatorPreferences.TranslatorSourceLanguage? {
        if (code.isNullOrBlank()) return null
        val normalized = code.trim().lowercase(Locale.ROOT).replace('_', '-')
        return when {
            normalized.startsWith("ja") || normalized == "jp" ->
                PageTranslatorPreferences.TranslatorSourceLanguage.JA
            normalized.startsWith("zh") || normalized == "cn" || normalized == "tw" || normalized == "hk" ->
                PageTranslatorPreferences.TranslatorSourceLanguage.ZH
            normalized.startsWith("ko") || normalized == "kr" ->
                PageTranslatorPreferences.TranslatorSourceLanguage.KO
            normalized.startsWith("es") || normalized == "mx" ->
                PageTranslatorPreferences.TranslatorSourceLanguage.ES
            normalized.startsWith("en") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.EN
            normalized.startsWith("de") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.DE
            normalized.startsWith("fr") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.FR
            normalized.startsWith("pt") || normalized == "br" ->
                PageTranslatorPreferences.TranslatorSourceLanguage.PT
            normalized.startsWith("it") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.IT
            normalized.startsWith("ru") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.RU
            else -> null
        }
    }

    private fun fromSourceName(name: String?): PageTranslatorPreferences.TranslatorSourceLanguage? {
        if (name.isNullOrBlank()) return null
        val n = name.lowercase(Locale.ROOT)
        return when {
            n.contains("(es)") || n.contains("spanish") || n.contains("español") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.ES
            n.contains("(ja)") || n.contains("(jp)") || (n.contains("raw") && n.contains("japan")) ->
                PageTranslatorPreferences.TranslatorSourceLanguage.JA
            n.contains("(zh)") || n.contains("(cn)") || n.contains("chinese") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.ZH
            n.contains("(ko)") || n.contains("(kr)") || n.contains("korean") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.KO
            n.contains("(en)") || n.contains("english") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.EN
            n.contains("(de)") || n.contains("german") || n.contains("deutsch") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.DE
            n.contains("(fr)") || n.contains("french") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.FR
            n.contains("(pt)") || n.contains("portuguese") || n.contains("brazil") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.PT
            n.contains("(it)") || n.contains("italian") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.IT
            n.contains("(ru)") || n.contains("russian") ->
                PageTranslatorPreferences.TranslatorSourceLanguage.RU
            else -> null
        }
    }

    private fun fromMangaType(manga: Manga): PageTranslatorPreferences.TranslatorSourceLanguage? {
        return when (manga.mangaType()) {
            MangaType.TYPE_MANHWA -> PageTranslatorPreferences.TranslatorSourceLanguage.KO
            MangaType.TYPE_MANHUA -> PageTranslatorPreferences.TranslatorSourceLanguage.ZH
            MangaType.TYPE_MANGA -> PageTranslatorPreferences.TranslatorSourceLanguage.JA
            MangaType.TYPE_COMIC, MangaType.TYPE_WEBTOON -> null
        }
    }

    private fun fromScript(
        title: String?,
        description: String?,
    ): PageTranslatorPreferences.TranslatorSourceLanguage? {
        val text = listOfNotNull(title, description?.take(800)).joinToString("\n")
        if (text.isBlank()) return null

        var ja = 0
        var zh = 0
        var ko = 0
        var latin = 0
        for (c in text) {
            when {
                c in '\u3040'..'\u30ff' -> ja++
                c in '\uac00'..'\ud7af' -> ko++
                c in '\u4e00'..'\u9fff' -> zh++
                c in 'A'..'Z' || c in 'a'..'z' || c in '\u00c0'..'\u024f' -> latin++
            }
        }

        val cjk = ja + zh + ko
        if (cjk == 0 && latin == 0) return null
        if (latin > cjk * 2) return null

        return when {
            ja > 0 && ja >= ko && ja * 2 >= zh -> PageTranslatorPreferences.TranslatorSourceLanguage.JA
            ko > 0 && ko >= ja && ko >= zh -> PageTranslatorPreferences.TranslatorSourceLanguage.KO
            zh > 0 && zh >= ja && zh >= ko -> PageTranslatorPreferences.TranslatorSourceLanguage.ZH
            else -> null
        }
    }

    private fun isLatinDominant(
        title: String?,
        description: String?,
        genres: List<String>?,
    ): Boolean {
        val text = buildString {
            title?.let { append(it).append('\n') }
            description?.take(500)?.let { append(it).append('\n') }
            genres?.take(20)?.joinTo(this, " ")
        }
        if (text.isBlank()) return false
        var latin = 0
        var cjk = 0
        for (c in text) {
            when {
                c in '\u3040'..'\u30ff' || c in '\u4e00'..'\u9fff' || c in '\uac00'..'\ud7af' -> cjk++
                c in 'A'..'Z' || c in 'a'..'z' || c in '\u00c0'..'\u024f' -> latin++
            }
        }
        return latin > 20 && latin > cjk * 3
    }

    private fun PageTranslatorPreferences.TranslatorSourceLanguage.isCjk(): Boolean {
        return this == PageTranslatorPreferences.TranslatorSourceLanguage.JA ||
            this == PageTranslatorPreferences.TranslatorSourceLanguage.ZH ||
            this == PageTranslatorPreferences.TranslatorSourceLanguage.KO
    }
}
