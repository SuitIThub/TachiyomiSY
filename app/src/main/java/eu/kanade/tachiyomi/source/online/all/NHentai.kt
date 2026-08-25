package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import android.net.Uri
import androidx.preference.ListPreference
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.PagePreviewInfo
import eu.kanade.tachiyomi.source.PagePreviewPage
import eu.kanade.tachiyomi.source.PagePreviewSource
import eu.kanade.tachiyomi.source.PreferenceScreen
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.nhentai.NHTags
import exh.source.NHENTAI_SOURCE_ID
import exh.util.dropBlank
import exh.util.trimAll
import exh.util.trimOrNull
import exh.util.urlImportFetchSearchManga
import exh.util.urlImportFetchSearchMangaSuspend
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CacheControl
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import tachiyomi.core.common.util.lang.runAsObservable
import tachiyomi.core.common.util.lang.withIOContext

class NHentai(val context: Context) :
    HttpSource(),
    ConfigurableSource,
    MetadataSource<NHentaiSearchMetadata, Response>,
    UrlImportableSource,
    NamespaceSource,
    PagePreviewSource {

    override val name = "NHentai"
    override val lang = "all"
    override val id = NHENTAI_SOURCE_ID
    override val baseUrl = NHentaiSearchMetadata.BASE_URL
    override val supportsLatest = true

    override val metaClass = NHentaiSearchMetadata::class
    override fun newMetaInstance() = NHentaiSearchMetadata()

    private val preferredTitle: Int
        get() = when (getSourcePreferences().getString(TITLE_PREF, "full")) {
            "full" -> NHentaiSearchMetadata.TITLE_TYPE_ENGLISH
            else -> NHentaiSearchMetadata.TITLE_TYPE_SHORT
        }

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = TITLE_PREF
            title = TITLE_PREF
            entries = arrayOf("Full Title", "Short Title")
            entryValues = arrayOf("full", "short")
            setDefaultValue("full")
            summary = "%s"
        }.also(screen::addPreference)
    }

    override fun popularMangaRequest(page: Int): Request {
        return searchRequest(page, "", FilterList(SortFilter(SortOption.POPULAR)))
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return searchRequest(page, "", FilterList())
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return searchRequest(page, query, filters)
    }

    private fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val combined = buildSearchQuery(query, filters)
        val sort = filters.firstNotNullOfOrNull { (it as? SortFilter)?.selected } ?: SortOption.RECENT
        val url = "$baseUrl/api/v2/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", combined.ifBlank { "pages:>0" })
            .addQueryParameter("page", page.toString())
        if (sort != SortOption.RECENT) {
            url.addQueryParameter("sort", sort.apiValue)
        }
        return GET(url.build(), headers)
    }

    private fun buildSearchQuery(query: String, filters: FilterList): String {
        val parts = mutableListOf<String>()
        query.trim().nullIfBlank()?.let { parts += it }

        val language = filters.firstNotNullOfOrNull { (it as? LanguageFilter)?.selected }
        if (language != null && language != LanguageOption.ALL) {
            val alreadyHasLanguage = parts.any { it.contains("language:", ignoreCase = true) } ||
                filters.filterIsInstance<Filter.AutoComplete>().any { filter ->
                    filter.state.any { it.contains("language:", ignoreCase = true) }
                }
            if (!alreadyHasLanguage) {
                parts += "language:${language.queryValue}"
            }
        }

        filters.filterIsInstance<Filter.AutoComplete>().forEach { filter ->
            filter.state.trimAll().dropBlank().forEach { tag ->
                parts += formatTagQuery(tag)
            }
        }

        return parts.joinToString(" ").trim()
    }

    private fun formatTagQuery(raw: String): String {
        val exclude = raw.startsWith("-")
        val body = raw.removePrefix("-").trim()
        val namespace = body.substringBefore(':', missingDelimiterValue = "").trim()
        val name = body.substringAfter(':', missingDelimiterValue = body).trim().trim('"')
        if (name.isBlank()) return raw.trim()

        val formatted = if (namespace.isNotBlank() && namespace != body) {
            if (name.any { it.isWhitespace() }) {
                """$namespace:"$name""""
            } else {
                "$namespace:$name"
            }
        } else if (name.any { it.isWhitespace() }) {
            """"$name""""
        } else {
            name
        }
        return if (exclude) "-$formatted" else formatted
    }

    override fun popularMangaParse(response: Response) = mangaListParse(response)
    override fun latestUpdatesParse(response: Response) = mangaListParse(response)
    override fun searchMangaParse(response: Response) = mangaListParse(response)

    private fun mangaListParse(response: Response): MangasPage {
        val body = requireJsonBody(response)
        val page = jsonParser.decodeFromString<JsonSearchPage>(body)
        val thumb = thumbServer
        val mangas = page.result.map { item ->
            val title = item.title?.pretty
                ?: item.title?.english
                ?: item.englishTitle
                ?: item.title?.japanese
                ?: item.japaneseTitle
                ?: item.id.toString()
            SManga(
                url = NHentaiSearchMetadata.nhIdToPath(item.id),
                title = title,
                thumbnail_url = absoluteUrl(thumb, item.thumbnailPath),
            )
        }
        val currentPage = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = when {
            page.numPages != null -> currentPage < page.numPages
            else -> mangas.isNotEmpty()
        }
        return MangasPage(mangas, hasNextPage)
    }

    @Deprecated("Use the non-RxJava API instead", replaceWith = ReplaceWith("getSearchManga"))
    override fun fetchSearchManga(page: Int, query: String, filters: FilterList) =
        urlImportFetchSearchManga(context, query) {
            searchByIdOrDefault(query) {
                @Suppress("DEPRECATION")
                super<HttpSource>.fetchSearchManga(page, query, filters)
            }
        }

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return urlImportFetchSearchMangaSuspend(context, query) {
            searchByIdOrDefaultSuspend(query) {
                super<HttpSource>.getSearchManga(page, query, filters)
            }
        }
    }

    private fun searchByIdOrDefault(query: String, fallback: () -> Observable<MangasPage>): Observable<MangasPage> {
        val id = parseGalleryId(query) ?: return fallback()
        return runAsObservable { fetchGalleryAsPage(id) }
    }

    private suspend fun searchByIdOrDefaultSuspend(query: String, fallback: suspend () -> MangasPage): MangasPage {
        val id = parseGalleryId(query) ?: return fallback()
        return fetchGalleryAsPage(id)
    }

    private suspend fun fetchGalleryAsPage(id: Long): MangasPage {
        val manga = SManga(url = NHentaiSearchMetadata.nhIdToPath(id), title = id.toString())
        return try {
            MangasPage(listOf(parseToManga(manga, client.newCall(mangaDetailsRequest(manga)).awaitSuccess())), false)
        } catch (_: Exception) {
            MangasPage(emptyList(), false)
        }
    }

    private fun parseGalleryId(query: String): Long? {
        val trimmed = query.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return null
        return trimmed.removePrefix("id:").removePrefix("#").trim().toLongOrNull()
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val id = NHentaiSearchMetadata.nhUrlToId(manga.url)
        return GET("$baseUrl/api/v2/galleries/$id", headers)
    }

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getMangaDetails"))
    override fun fetchMangaDetails(manga: SManga): Observable<SManga> {
        return runAsObservable {
            val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            parseToManga(manga, response).apply { initialized = true }
        }
    }

    override suspend fun parseIntoMetadata(metadata: NHentaiSearchMetadata, input: Response) {
        ensureConfig()
        val jsonResponse = jsonParser.decodeFromString<JsonResponse>(requireJsonBody(input))
        val thumb = thumbServer
        val image = imageServer

        with(metadata) {
            nhId = jsonResponse.id
            uploadDate = jsonResponse.uploadDate
            favoritesCount = jsonResponse.numFavorites
            mediaId = jsonResponse.mediaId

            jsonResponse.title?.let { title ->
                japaneseTitle = title.japanese
                shortTitle = title.pretty
                englishTitle = title.english
            }

            preferredTitle = this@NHentai.preferredTitle

            coverImageUrl = absoluteUrl(thumb, jsonResponse.cover?.path)
                ?: absoluteUrl(thumb, jsonResponse.thumbnail?.path)

            pageImagePreviewUrls = jsonResponse.pages.mapNotNull { absoluteUrl(thumb, it.thumbnail) }
            pageImageUrls = jsonResponse.pages.mapNotNull { absoluteUrl(image, it.path) }

            scanlator = jsonResponse.scanlator?.trimOrNull()

            tags.clear()
            jsonResponse.tags.filter {
                it.type != null && it.name != null
            }.mapTo(tags) {
                RaisedTag(
                    it.type!!,
                    it.name!!,
                    if (it.type == NHentaiSearchMetadata.NHENTAI_CATEGORIES_NAMESPACE) {
                        RaisedSearchMetadata.TAG_TYPE_VIRTUAL
                    } else {
                        NHentaiSearchMetadata.TAG_TYPE_DEFAULT
                    },
                )
            }
        }
    }

    @Deprecated("Use the 1.x API instead", replaceWith = ReplaceWith("getChapterList"))
    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> {
        return runAsObservable { chapterList(manga) }
    }

    private suspend fun chapterList(manga: SManga): List<SChapter> {
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
        }
        return listOf(
            SChapter(
                url = manga.url,
                name = manga.title.ifBlank { "Chapter" },
                chapter_number = 1f,
                date_upload = metadata.uploadDate?.times(1000) ?: 0L,
                scanlator = metadata.scanlator,
            ),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        ensureConfig()
        val metadata = fetchOrLoadMetadata(getMangaId.awaitId(chapter.url, id)) {
            client.newCall(mangaDetailsRequest(SManga(url = chapter.url, title = ""))).awaitSuccess()
        }
        val urls = metadata.pageImageUrls.ifEmpty {
            client.newCall(mangaDetailsRequest(SManga(url = chapter.url, title = ""))).awaitSuccess().let { response ->
                val fresh = newMetaInstance()
                parseIntoMetadata(fresh, response)
                fresh.pageImageUrls
            }
        }
        return urls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override fun imageRequest(page: Page): Request {
        return GET(page.imageUrl!!, headers)
    }

    override fun getFilterList(): FilterList {
        return FilterList(
            Filter.Header("Prefix a tag with - to exclude it"),
            AutoCompleteTags(),
            LanguageFilter(),
            SortFilter(),
        )
    }

    class AutoCompleteTags :
        Filter.AutoComplete(
            name = "Tags",
            hint = "Search tags, artists, languages…",
            values = NHTags.getAutoCompleteValues(),
            skipAutoFillTags = NHTags.getNamespaces().map { "$it:" },
            validPrefixes = listOf("-"),
            state = emptyList(),
        )

    private enum class LanguageOption(val displayName: String, val queryValue: String) {
        ALL("All", ""),
        ENGLISH("English", "english"),
        JAPANESE("Japanese", "japanese"),
        CHINESE("Chinese", "chinese"),
        KOREAN("Korean", "korean"),
        SPANISH("Spanish", "spanish"),
        RUSSIAN("Russian", "russian"),
        FRENCH("French", "french"),
        PORTUGUESE("Portuguese", "portuguese"),
        THAI("Thai", "thai"),
        VIETNAMESE("Vietnamese", "vietnamese"),
        GERMAN("German", "german"),
        ITALIAN("Italian", "italian"),
        ;

        override fun toString() = displayName
    }

    private class LanguageFilter : Filter.Select<LanguageOption>(
        "Language",
        LanguageOption.entries.toTypedArray(),
    ) {
        val selected: LanguageOption
            get() = values.getOrElse(state) { LanguageOption.ALL }
    }

    private enum class SortOption(val displayName: String, val apiValue: String) {
        RECENT("Recent", "newest"),
        POPULAR("Popular", "popular"),
        POPULAR_TODAY("Popular today", "popular-today"),
        POPULAR_WEEK("Popular this week", "popular-week"),
        POPULAR_MONTH("Popular this month", "popular-month"),
        ;

        override fun toString() = displayName
    }

    private class SortFilter(selection: SortOption = SortOption.RECENT) : Filter.Select<SortOption>(
        "Sort by",
        SortOption.entries.toTypedArray(),
        SortOption.entries.indexOf(selection).coerceAtLeast(0),
    ) {
        val selected: SortOption
            get() = values.getOrElse(state) { SortOption.RECENT }
    }

    override val matchingHosts = listOf(
        "nhentai.net",
        "www.nhentai.net",
    )

    override suspend fun mapUrlToMangaUrl(uri: Uri): String? {
        if (uri.pathSegments.firstOrNull()?.lowercase() != "g") {
            return null
        }
        val id = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return null
        return NHentaiSearchMetadata.nhIdToPath(id)
    }

    override suspend fun getPagePreviewList(manga: SManga, chapters: List<SChapter>, page: Int): PagePreviewPage {
        ensureConfig()
        val metadata = fetchOrLoadMetadata(manga.id()) {
            client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
        }
        return PagePreviewPage(
            page,
            metadata.pageImagePreviewUrls.mapIndexed { index, url ->
                PagePreviewInfo(
                    index + 1,
                    imageUrl = url,
                )
            },
            false,
            1,
        )
    }

    @Volatile
    private var nhConfig: JsonConfig? = null

    private suspend fun ensureConfig() {
        if (nhConfig != null) return
        getNhConfig()
    }

    private val thumbServer
        get() = nhConfig?.thumbServers?.randomOrNull() ?: "https://t1.nhentai.net"

    private val imageServer
        get() = nhConfig?.imageServers?.randomOrNull() ?: "https://i1.nhentai.net"

    private suspend fun getNhConfig() {
        try {
            val response = withIOContext {
                client.newCall(GET("$baseUrl/api/v2/config", headers)).awaitSuccess()
            }
            nhConfig = jsonParser.decodeFromString<JsonConfig>(response.body.string())
        } catch (_: Exception) {
            nhConfig = JsonConfig(
                (1..4).map { n -> "https://i$n.nhentai.net" },
                (1..4).map { n -> "https://t$n.nhentai.net" },
            )
        }
    }

    override suspend fun fetchPreviewImage(page: PagePreviewInfo, cacheControl: CacheControl?): Response {
        return client.newCachelessCallWithProgress(
            if (cacheControl != null) {
                GET(page.imageUrl, headers, cache = cacheControl)
            } else {
                GET(page.imageUrl, headers)
            },
            page,
        ).awaitSuccess()
    }

    companion object {
        const val otherId = NHENTAI_SOURCE_ID

        val LANGUAGE_SOURCE_IDS = listOf(
            3122156392225024195L, // en
            4726175775739752699L, // ja
            2203215402871965477L, // zh
        )

        @OptIn(ExperimentalSerializationApi::class)
        private val jsonParser = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        private const val TITLE_PREF = "Display manga title as:"

        private fun requireJsonBody(response: Response): String {
            val body = response.body.string()
            val trimmed = body.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return body
            }
            throw Exception("NHentai blocked the request. Open WebView, pass Cloudflare, then retry.")
        }

        private fun absoluteUrl(server: String?, path: String?): String? {
            if (path.isNullOrBlank()) return null
            if (path.startsWith("http://") || path.startsWith("https://")) return path
            val base = server?.trimEnd('/') ?: return path
            return "$base/${path.trimStart('/')}"
        }

        private fun String.nullIfBlank(): String? = trimOrNull()
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class JsonConfig(
        @JsonNames("imageServers")
        @SerialName("image_servers")
        val imageServers: List<String> = emptyList(),
        @JsonNames("thumbServers")
        @SerialName("thumb_servers")
        val thumbServers: List<String> = emptyList(),
    )

    @Serializable
    data class JsonSearchPage(
        val result: List<JsonListItem> = emptyList(),
        @SerialName("num_pages")
        val numPages: Int? = null,
    )

    @Serializable
    data class JsonListItem(
        val id: Long,
        @SerialName("media_id")
        val mediaId: String? = null,
        val thumbnail: JsonElement? = null,
        @SerialName("english_title")
        val englishTitle: String? = null,
        @SerialName("japanese_title")
        val japaneseTitle: String? = null,
        val title: JsonTitle? = null,
        val cover: JsonPage? = null,
    ) {
        val thumbnailPath: String?
            get() = when (val value = thumbnail) {
                is JsonPrimitive -> value.contentOrNull
                is JsonObject -> value["path"]?.jsonPrimitive?.contentOrNull
                else -> cover?.path
            } ?: cover?.path
    }

    @Serializable
    data class JsonResponse(
        val id: Long,
        @SerialName("media_id")
        val mediaId: String? = null,
        val title: JsonTitle? = null,
        val cover: JsonPage? = null,
        val thumbnail: JsonPage? = null,
        val scanlator: String? = null,
        @SerialName("upload_date")
        val uploadDate: Long? = null,
        val tags: List<JsonTag> = emptyList(),
        @SerialName("num_pages")
        val numPages: Int? = null,
        @SerialName("num_favorites")
        val numFavorites: Long? = null,
        val pages: List<JsonPage> = emptyList(),
    )

    @Serializable
    data class JsonTitle(
        val english: String? = null,
        val japanese: String? = null,
        val pretty: String? = null,
    )

    @Serializable
    data class JsonPage(
        val path: String? = null,
        val width: Long? = null,
        val height: Long? = null,
        val thumbnail: String? = null,
    )

    @Serializable
    data class JsonTag(
        val id: Long? = null,
        val type: String? = null,
        val name: String? = null,
        val url: String? = null,
        val count: Long? = null,
    )
}
