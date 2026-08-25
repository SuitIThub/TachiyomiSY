package exh.nhentai

import exh.eh.EHTags

/**
 * Offline tag dictionary for NHentai autocomplete.
 *
 * NHentai uses a smaller set of namespaces than E-Hentai (`tag`, `artist`, `character`,
 * `parody`, `group`, `language`, `category`). E-Hentai's `female`/`male`/`mixed`/`other`
 * tags are mapped onto `tag:`.
 */
object NHTags {
    fun getNamespaces(): List<String> = NAMESPACES

    fun getAllTags(): List<String> = cachedAllTags

    fun getAutoCompleteValues(): List<String> = cachedAutoCompleteValues

    private val NAMESPACES = listOf(
        "tag",
        "artist",
        "character",
        "parody",
        "group",
        "language",
        "category",
    )

    private val CATEGORIES = listOf(
        "category:doujinshi",
        "category:manga",
        "category:artistcg",
        "category:gamecg",
        "category:western",
        "category:non-h",
        "category:imageset",
        "category:cosplay",
        "category:asianporn",
        "category:misc",
    )

    private val LANGUAGES = listOf(
        "language:arabic",
        "language:chinese",
        "language:czech",
        "language:dutch",
        "language:english",
        "language:french",
        "language:german",
        "language:hungarian",
        "language:indonesian",
        "language:italian",
        "language:japanese",
        "language:korean",
        "language:polish",
        "language:portuguese",
        "language:rewrite",
        "language:russian",
        "language:spanish",
        "language:speechless",
        "language:thai",
        "language:translated",
        "language:turkish",
        "language:vietnamese",
    )

    private val cachedAllTags: List<String> by lazy {
        val mapped = LinkedHashSet<String>(4096)
        mapped += CATEGORIES
        mapped += LANGUAGES
        for (tag in EHTags.getAllTags()) {
            val namespace = tag.substringBefore(':', missingDelimiterValue = "")
            val name = tag.substringAfter(':', missingDelimiterValue = tag)
            if (name.isBlank()) continue
            when (namespace) {
                "female", "male", "mixed", "other", "location", "cosplayer", "reclass" ->
                    mapped += "tag:$name"
                "language", "parody", "character", "group", "artist" ->
                    mapped += "$namespace:$name"
            }
        }
        mapped.toList()
    }

    private val cachedAutoCompleteValues: List<String> by lazy {
        NAMESPACES.map { "$it:" } + cachedAllTags
    }
}
