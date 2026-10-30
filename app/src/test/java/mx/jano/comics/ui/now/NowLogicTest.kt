package mx.jano.comics.ui.now

import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class NowLogicTest {
    // Dos series: A (a1..a5) y B (b1..b3).
    private val a = (1..5).map { Book("a$it", "A $it.cbz", "A", "A/A $it.cbz", 1, md5 = "a$it") }
    private val b = (1..3).map { Book("b$it", "B $it.cbz", "B", "B/B $it.cbz", 1, md5 = "b$it") }
    private val all = (a + b).associateBy { it.contentKey }
    private fun next(book: Book): Book? {
        val list = if (book.folderId == "A") a else b
        return list.getOrNull(list.indexOfFirst { it.id == book.id } + 1)
    }
    private fun p(book: Book, page: Int, finished: Boolean, at: Long) = ReadingProgress(book.contentKey, page, 30, at, finished)

    private fun compute(progress: List<ReadingProgress>): NowLists {
        val byKey = progress.associateBy { it.contentKey }
        return NowLogic.compute(
            recent = progress,
            bookByKey = all::get,
            stateOf = { byKey[it.contentKey]?.state ?: ReadState.UNREAD },
            nextAfter = ::next,
        )
    }

    @Test fun continueHasTheBooksInProgressMostRecentFirst() {
        val r = compute(listOf(p(a[1], 5, false, 10), p(b[0], 3, false, 20), p(a[0], 29, true, 5)))
        assertEquals(listOf("b1", "a2"), r.continueReading.map { it.book.id })
    }

    @Test fun nextIsTheFirstUnreadAfterTheLastOpenedOfEachSeries() {
        // A: a1 y a2 leídos, a3 sin leer → siguiente a3. B: b1 en curso → siguiente b2.
        val r = compute(listOf(p(a[0], 29, true, 1), p(a[1], 29, true, 2), p(b[0], 4, false, 3)))
        assertEquals(listOf("b2", "a3"), r.next.map { it.id })
    }

    @Test fun nextSkipsBooksAlreadyReadOrInProgress() {
        // Último abierto a1 (leído); a2 leído antes, a3 en curso → siguiente a4.
        val r = compute(listOf(p(a[0], 29, true, 10), p(a[1], 29, true, 1), p(a[2], 7, false, 2)))
        assertEquals(listOf("a4"), r.next.map { it.id })
        assertEquals(listOf("a3"), r.continueReading.map { it.book.id })
    }

    @Test fun aFinishedSeriesHasNoNext() {
        val r = compute(b.mapIndexed { i, book -> p(book, 29, true, i.toLong()) })
        assertEquals(emptyList<Book>(), r.next)
        assertEquals(true, r.isEmpty)
    }

    @Test fun unknownBooksAreIgnored() {
        val ghost = ReadingProgress("no-existe:1", 3, 10, 99, false)
        val r = compute(listOf(ghost, p(a[0], 2, false, 1)))
        assertEquals(listOf("a1"), r.continueReading.map { it.book.id })
        assertEquals(listOf("a2"), r.next.map { it.id })
    }
}
