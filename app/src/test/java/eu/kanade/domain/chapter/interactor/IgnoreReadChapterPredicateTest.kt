package eu.kanade.domain.chapter.interactor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.library.service.LibraryPreferences.IgnoreReadChaptersOnUpdate

@Execution(ExecutionMode.CONCURRENT)
class IgnoreReadChapterPredicateTest {

    private fun chapter(number: Double, read: Boolean) =
        Chapter.create().copy(chapterNumber = number, read = read)

    private fun predicate(mode: IgnoreReadChaptersOnUpdate, dbChapters: List<Chapter>): (Double) -> Boolean {
        val readNumbers = dbChapters.filter { it.read && it.isRecognizedNumber }.map { it.chapterNumber }.toSet()
        return SyncChaptersWithSource.ignoreReadChapterPredicate(mode, dbChapters, readNumbers)
    }

    // Read 1, 2, 4, 5 — chapter 3 deliberately skipped — 6 and 7 unread
    private val skippedThree = listOf(
        chapter(1.0, read = true),
        chapter(2.0, read = true),
        chapter(3.0, read = false),
        chapter(4.0, read = true),
        chapter(5.0, read = true),
        chapter(6.0, read = false),
        chapter(7.0, read = false),
    )

    @Test
    fun `Nothing never marks chapters as read`() {
        val p = predicate(IgnoreReadChaptersOnUpdate.Nothing, skippedThree)
        listOf(1.0, 3.0, 4.0, 5.5, 8.0).forEach { p(it) shouldBe false }
    }

    @Test
    fun `ExistingRead only marks previously read chapter numbers`() {
        val p = predicate(IgnoreReadChaptersOnUpdate.ExistingRead, skippedThree)
        p(1.0) shouldBe true
        p(4.0) shouldBe true
        p(3.0) shouldBe false
        p(4.5) shouldBe false
        p(6.0) shouldBe false
        p(8.0) shouldBe false
    }

    @Test
    fun `BeforeUnreadBlock marks re-added and new chapters before the unread block`() {
        val p = predicate(IgnoreReadChaptersOnUpdate.BeforeUnreadBlock, skippedThree)
        p(4.0) shouldBe true // re-added read chapter
        p(4.5) shouldBe true // new chapter before the unread block
        p(5.5) shouldBe true
        p(0.5) shouldBe true
    }

    @Test
    fun `BeforeUnreadBlock keeps deliberately skipped and unread block chapters unread`() {
        val p = predicate(IgnoreReadChaptersOnUpdate.BeforeUnreadBlock, skippedThree)
        p(3.0) shouldBe false // skipped by the user
        p(6.0) shouldBe false // start of unread block
        p(6.5) shouldBe false
        p(8.0) shouldBe false // genuinely new chapter
    }

    @Test
    fun `BeforeUnreadBlock without unread block uses the last read chapter as limit`() {
        val allRead = listOf(chapter(1.0, read = true), chapter(2.0, read = true), chapter(3.0, read = true))
        val p = predicate(IgnoreReadChaptersOnUpdate.BeforeUnreadBlock, allRead)
        p(2.0) shouldBe true
        p(3.0) shouldBe true
        p(3.5) shouldBe false
        p(4.0) shouldBe false
    }

    @Test
    fun `BeforeUnreadBlock does nothing when nothing was read`() {
        val noneRead = listOf(chapter(1.0, read = false), chapter(2.0, read = false))
        val p = predicate(IgnoreReadChaptersOnUpdate.BeforeUnreadBlock, noneRead)
        p(1.0) shouldBe false
        p(0.5) shouldBe false
    }

    @Test
    fun `BeforeUnreadBlock prefers read state for duplicate chapter numbers`() {
        // Same number exists read (one scanlator) and unread (another scanlator)
        val duplicates = listOf(chapter(1.0, read = true), chapter(2.0, read = true), chapter(2.0, read = false))
        val p = predicate(IgnoreReadChaptersOnUpdate.BeforeUnreadBlock, duplicates)
        p(2.0) shouldBe true
    }
}
