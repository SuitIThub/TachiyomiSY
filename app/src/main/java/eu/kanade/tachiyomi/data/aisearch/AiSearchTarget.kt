package eu.kanade.tachiyomi.data.aisearch

import eu.kanade.tachiyomi.source.Source
import exh.source.isEhBasedSource
import exh.source.isNhBasedSource

enum class AiSearchTarget {
    EHENTAI,
    NHENTAI,
}

fun Source.aiSearchTargetOrNull(): AiSearchTarget? = when {
    isEhBasedSource() -> AiSearchTarget.EHENTAI
    isNhBasedSource() -> AiSearchTarget.NHENTAI
    else -> null
}
