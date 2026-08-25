package eu.kanade.tachiyomi.data.translator

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue

/**
 * Coordinates page translation jobs, caching, and holder upgrades.
 */
class PageTranslatorManager(
    private val preferences: PageTranslatorPreferences,
    private val engine: PageTranslatorEngine,
    private val cache: PageTranslatorCache,
    private val getManga: GetManga,
    private val getFlatMetadata: GetFlatMetadataById,
    private val languageInferrer: MangaSourceLanguageInferrer,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, PageTranslatorSession>()
    private val queue = PriorityBlockingQueue<PageTranslatorJob>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val inferredByManga = ConcurrentHashMap<Long, PageTranslatorPreferences.TranslatorSourceLanguage>()
    private val mutex = Mutex()
    private val inferMutex = Mutex()

    init {
        repeat(WORKER_COUNT) {
            scope.launch {
                flow {
                    while (true) {
                        emit(runInterruptible { queue.take() })
                    }
                }.collect { processJob(it) }
            }
        }
    }

    fun isEnabled(mangaId: Long): Boolean = preferences.isEnabledForManga(mangaId)

    fun getCachedSource(cacheKey: String): BufferedSource? {
        val file = cache.getFile(cacheKey) ?: return null
        return try {
            Buffer().readFrom(file.inputStream())
        } catch (_: Exception) {
            null
        }
    }

    fun sessionFor(cacheKey: String): PageTranslatorSession? = sessions[cacheKey]

    suspend fun resolveSourceLanguage(mangaId: Long): PageTranslatorPreferences.TranslatorSourceLanguage {
        ensureInferred(mangaId)
        return preferences.resolvedSourceLanguage(mangaId, inferredByManga[mangaId])
    }

    fun buildCacheKey(
        page: ReaderPage,
        mangaId: Long,
        transformKey: String = "",
        sourceLanguage: PageTranslatorPreferences.TranslatorSourceLanguage =
            preferences.resolvedSourceLanguage(mangaId, inferredByManga[mangaId]),
    ): String {
        val identity = page.imageUrl
            ?: "${page.chapter.chapter.id}_${page.index}"
        return engine.buildCacheKey(
            imageIdentity = identity,
            sourceLanguage = sourceLanguage,
            targetLanguage = preferences.resolvedTargetLanguage(mangaId),
            provider = preferences.provider.get(),
            transformKey = transformKey,
            mergeMode = preferences.mergeMode.get(),
        )
    }

    /**
     * Returns a translated [BufferedSource] immediately when cached; otherwise enqueues background
     * work and returns null so the caller can show the original image first.
     */
    suspend fun translateOrEnqueue(
        page: ReaderPage,
        mangaId: Long,
        imageBytes: ByteArray,
        transformKey: String = "",
        priority: Int = PRIORITY_VISIBLE,
    ): BufferedSource? {
        if (!isEnabled(mangaId) || imageBytes.isEmpty()) return null

        val sourceLanguage = resolveSourceLanguage(mangaId)
        val cacheKey = buildCacheKey(page, mangaId, transformKey, sourceLanguage)
        getCachedSource(cacheKey)?.let { return it }

        val session = sessions.getOrPut(cacheKey) {
            PageTranslatorSession(
                cacheKey = cacheKey,
                mangaId = mangaId,
                sourceLanguage = sourceLanguage,
                targetLanguage = preferences.resolvedTargetLanguage(mangaId),
            )
        }

        if (session.state.value is PageTranslationState.Ready) {
            getCachedSource(cacheKey)?.let { return it }
        }

        enqueue(
            PageTranslatorJob(
                session = session,
                imageBytes = imageBytes,
                priority = priority,
            ),
        )
        return null
    }

    fun enqueuePreload(
        page: ReaderPage,
        mangaId: Long,
        imageBytes: ByteArray,
        transformKey: String = "",
    ) {
        if (!isEnabled(mangaId) || imageBytes.isEmpty()) return
        scope.launch {
            val sourceLanguage = resolveSourceLanguage(mangaId)
            val cacheKey = buildCacheKey(page, mangaId, transformKey, sourceLanguage)
            if (cache.isInCache(cacheKey) || cacheKey in inFlight) return@launch

            val session = sessions.getOrPut(cacheKey) {
                PageTranslatorSession(
                    cacheKey = cacheKey,
                    mangaId = mangaId,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = preferences.resolvedTargetLanguage(mangaId),
                )
            }
            enqueue(
                PageTranslatorJob(
                    session = session,
                    imageBytes = imageBytes,
                    priority = PRIORITY_PRELOAD,
                ),
            )
        }
    }

    fun cancelChapterWork() {
        queue.clear()
        inFlight.clear()
        sessions.values.forEach {
            if (it.state.value is PageTranslationState.Translating) {
                it.update(PageTranslationState.Idle)
            }
        }
    }

    fun close() {
        cancelChapterWork()
        scope.cancel()
    }

    private suspend fun ensureInferred(mangaId: Long) {
        if (inferredByManga.containsKey(mangaId)) return
        // Only needed when Auto is active (global or via default manga override path).
        val override = preferences.mangaSourceLanguage(mangaId).get()
        val global = preferences.sourceLanguage.get()
        if (override != PageTranslatorPreferences.TranslatorSourceLanguage.DEFAULT) return
        if (global != PageTranslatorPreferences.TranslatorSourceLanguage.AUTO) return

        inferMutex.withLock {
            if (inferredByManga.containsKey(mangaId)) return
            try {
                val manga = getManga.await(mangaId) ?: return
                val flat = getFlatMetadata.await(mangaId)
                val inferred = languageInferrer.infer(manga, flat)
                if (inferred != null) {
                    inferredByManga[mangaId] = inferred
                    logcat { "Inferred source language $inferred for manga $mangaId" }
                }
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Failed to infer source language for manga $mangaId" }
            }
        }
    }

    private suspend fun enqueue(job: PageTranslatorJob) {
        mutex.withLock {
            if (job.session.cacheKey in inFlight) return
            if (cache.isInCache(job.session.cacheKey)) {
                cache.getFile(job.session.cacheKey)?.absolutePath?.let { path ->
                    job.session.update(PageTranslationState.Ready(path))
                }
                return
            }
            inFlight.add(job.session.cacheKey)
            job.session.update(PageTranslationState.Translating)
        }
        queue.offer(job)
    }

    private suspend fun processJob(job: PageTranslatorJob) {
        val key = job.session.cacheKey
        try {
            val bytes = engine.translatePage(
                imageBytes = job.imageBytes,
                cacheKey = key,
                sourceLanguage = job.session.sourceLanguage,
                targetLanguage = job.session.targetLanguage,
                mergeMode = preferences.mergeMode.get(),
            )
            cache.getFile(key)?.let { file ->
                job.session.update(PageTranslationState.Ready(file.absolutePath))
                return
            }
            cache.put(key, bytes)
            val created = cache.getFile(key)
            job.session.update(
                if (created != null) {
                    PageTranslationState.Ready(created.absolutePath)
                } else {
                    PageTranslationState.Error(IllegalStateException("Missing cache after write"))
                },
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Translator job failed for $key" }
            job.session.update(PageTranslationState.Error(e))
        } finally {
            inFlight.remove(key)
        }
    }

    fun openTranslatedStream(filePath: String): BufferedSource {
        return Buffer().readFrom(java.io.File(filePath).inputStream())
    }

    fun asInputStreamFactory(bytes: ByteArray): () -> ByteArrayInputStream = {
        ByteArrayInputStream(bytes)
    }

    companion object {
        const val PRIORITY_VISIBLE = 0
        const val PRIORITY_PRELOAD = 10
        private const val WORKER_COUNT = 2
    }
}
