package eu.kanade.tachiyomi.data.translator

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.flow.collectLatest
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.lang.withIOContext

/**
 * Shared helper used by pager/webtoon holders to prefer cached translations and upgrade in place.
 */
object PageTranslationHelper {

    suspend fun maybeReplaceWithTranslation(
        manager: PageTranslatorManager,
        page: ReaderPage,
        mangaId: Long?,
        source: BufferedSource,
        transformKey: String = "",
    ): BufferedSource {
        if (mangaId == null || !manager.isEnabled(mangaId)) return source

        val bytes = withIOContext { source.readByteArray() }
        val translated = manager.translateOrEnqueue(
            page = page,
            mangaId = mangaId,
            imageBytes = bytes,
            transformKey = transformKey,
            priority = PageTranslatorManager.PRIORITY_VISIBLE,
        )
        return translated ?: Buffer().write(bytes)
    }

    suspend fun observeTranslationUpgrade(
        manager: PageTranslatorManager,
        page: ReaderPage,
        mangaId: Long?,
        transformKey: String = "",
        onReady: suspend (BufferedSource) -> Unit,
    ) {
        if (mangaId == null || !manager.isEnabled(mangaId)) return
        val sourceLanguage = manager.resolveSourceLanguage(mangaId)
        val cacheKey = manager.buildCacheKey(page, mangaId, transformKey, sourceLanguage)
        val session = manager.sessionFor(cacheKey) ?: return
        session.state.collectLatest { state ->
            if (state is PageTranslationState.Ready && state.filePath.isNotEmpty()) {
                onReady(manager.openTranslatedStream(state.filePath))
            }
        }
    }

    fun enqueueAdjacentPreload(
        manager: PageTranslatorManager,
        pages: List<ReaderPage>,
        currentIndex: Int,
        mangaId: Long?,
        preloadCount: Int,
        readBytes: (ReaderPage) -> ByteArray?,
    ) {
        if (mangaId == null || !manager.isEnabled(mangaId) || preloadCount <= 0) return
        val end = (currentIndex + preloadCount).coerceAtMost(pages.lastIndex)
        for (i in (currentIndex + 1)..end) {
            val page = pages[i]
            val bytes = readBytes(page) ?: continue
            manager.enqueuePreload(page, mangaId, bytes)
        }
    }
}
