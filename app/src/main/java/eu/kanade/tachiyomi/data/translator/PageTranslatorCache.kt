package eu.kanade.tachiyomi.data.translator

import android.content.Context
import android.text.format.Formatter
import com.jakewharton.disklrucache.DiskLruCache
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException

class PageTranslatorCache(
    private val context: Context,
    preferences: PageTranslatorPreferences,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var diskCache = setupDiskCache(preferences.cacheSize.get().toLong())

    init {
        preferences.cacheSize.changes()
            .drop(1)
            .onEach { sizeMb ->
                val old = diskCache
                diskCache = setupDiskCache(sizeMb.toLong())
                runCatching { old.close() }
            }
            .launchIn(scope)
    }

    val readableSize: String
        get() = Formatter.formatFileSize(context, DiskUtil.getDirectorySize(diskCache.directory))

    fun isInCache(key: String): Boolean {
        return try {
            diskCache.get(DiskUtil.hashKeyForDisk(key)).use { it != null }
        } catch (_: Exception) {
            false
        }
    }

    fun getFile(key: String): File? {
        return try {
            val hashed = DiskUtil.hashKeyForDisk(key)
            diskCache.get(hashed).use { snapshot ->
                snapshot ?: return null
                File(diskCache.directory, "$hashed.0").takeIf { it.exists() }
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to read translator cache" }
            null
        }
    }

    fun put(key: String, bytes: ByteArray) {
        val hashed = DiskUtil.hashKeyForDisk(key)
        var editor: DiskLruCache.Editor? = null
        try {
            editor = diskCache.edit(hashed) ?: return
            editor.newOutputStream(0).use { it.write(bytes) }
            editor.commit()
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Failed to write translator cache" }
            try {
                editor?.abort()
            } catch (_: Exception) {
            }
        }
    }

    fun clear(sizeMb: Long = 100) {
        try {
            val old = diskCache
            old.delete()
            diskCache = setupDiskCache(sizeMb)
        } catch (e: IOException) {
            logcat(LogPriority.WARN, e) { "Failed to clear translator cache" }
        }
    }

    private fun setupDiskCache(cacheSizeMb: Long): DiskLruCache {
        return DiskLruCache.open(
            File(context.cacheDir, CACHE_DIR),
            APP_VERSION,
            VALUE_COUNT,
            cacheSizeMb.coerceAtLeast(1) * 1024 * 1024,
        )
    }

    companion object {
        private const val CACHE_DIR = "page_translator_disk_cache"
        private const val APP_VERSION = 1
        private const val VALUE_COUNT = 1
        const val RENDER_VERSION = 9
    }
}
