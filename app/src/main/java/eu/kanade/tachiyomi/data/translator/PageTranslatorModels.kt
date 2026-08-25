package eu.kanade.tachiyomi.data.translator

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TextOrientation {
    /** Horizontal left-to-right (Latin, Korean often, some CJK). */
    HORIZONTAL_LTR,

    /** Vertical columns, top-to-bottom then right-to-left (typical Japanese manga). */
    VERTICAL_RTL,

    /** Vertical columns, top-to-bottom then left-to-right (some Chinese layouts). */
    VERTICAL_LTR,
}

data class OcrTextBlock(
    val text: String,
    val boundingBox: Rect,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL_LTR,
)

data class TranslatedTextBlock(
    val original: String,
    val translated: String,
    val boundingBox: Rect,
    val orientation: TextOrientation = TextOrientation.HORIZONTAL_LTR,
)

data class PageTranslatorSession(
    val cacheKey: String,
    val mangaId: Long,
    val sourceLanguage: PageTranslatorPreferences.TranslatorSourceLanguage,
    val targetLanguage: PageTranslatorPreferences.TranslatorTargetLanguage,
) {
    private val _state = MutableStateFlow<PageTranslationState>(PageTranslationState.Idle)
    val state: StateFlow<PageTranslationState> = _state.asStateFlow()

    fun update(state: PageTranslationState) {
        _state.value = state
    }
}

sealed interface PageTranslationState {
    data object Idle : PageTranslationState
    data object Translating : PageTranslationState
    data class Ready(val filePath: String) : PageTranslationState
    data class Error(val error: Throwable) : PageTranslationState
    data object Skipped : PageTranslationState
}

data class PageTranslatorJob(
    val session: PageTranslatorSession,
    val imageBytes: ByteArray,
    val priority: Int,
) : Comparable<PageTranslatorJob> {
    override fun compareTo(other: PageTranslatorJob): Int {
        return when {
            priority < other.priority -> -1
            priority > other.priority -> 1
            else -> 0
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PageTranslatorJob) return false
        return session.cacheKey == other.session.cacheKey
    }

    override fun hashCode(): Int = session.cacheKey.hashCode()
}
