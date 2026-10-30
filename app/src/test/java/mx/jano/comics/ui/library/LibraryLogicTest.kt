package mx.jano.comics.ui.library

import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {
    private fun book(id: String) = Book(id = id, name = "$id.cbz", folderId = "f", path = "f/$id.cbz", size = 10)
    private fun progress(page: Int, count: Int, finished: Boolean = false) = ReadingProgress("k", page, count, 0, finished)

    @Test fun filtersMatchTheirState() {
        val cloud = Availability.CLOUD
        assertTrue(LibraryLogic.matches(LibraryFilter.ALL, ReadState.FINISHED, cloud))
        assertTrue(LibraryLogic.matches(LibraryFilter.TO_READ, ReadState.UNREAD, cloud))
        assertFalse(LibraryLogic.matches(LibraryFilter.TO_READ, ReadState.READING, cloud))
        assertTrue(LibraryLogic.matches(LibraryFilter.READING, ReadState.READING, cloud))
        assertTrue(LibraryLogic.matches(LibraryFilter.READ, ReadState.FINISHED, cloud))
        assertFalse(LibraryLogic.matches(LibraryFilter.READ, ReadState.READING, cloud))
    }

    @Test fun offlineFilterIsAboutTheTabletNotTheReadState() {
        assertTrue(LibraryLogic.matches(LibraryFilter.OFFLINE, ReadState.UNREAD, Availability.LOCAL))
        assertTrue(LibraryLogic.matches(LibraryFilter.OFFLINE, ReadState.FINISHED, Availability.PINNED))
        assertFalse(LibraryLogic.matches(LibraryFilter.OFFLINE, ReadState.UNREAD, Availability.DOWNLOADING))
        assertFalse(LibraryLogic.matches(LibraryFilter.OFFLINE, ReadState.UNREAD, Availability.CLOUD))
    }

    @Test fun statsCountEveryStateAndTheLocalCopies() {
        val books = (1..6).map { book("b$it") }
        val states = mapOf("b1" to ReadState.FINISHED, "b2" to ReadState.FINISHED, "b3" to ReadState.READING)
        val local = setOf("b1", "b4")
        val s = LibraryLogic.stats(
            books,
            stateOf = { states[it.id] ?: ReadState.UNREAD },
            availabilityOf = { if (it.id in local) Availability.LOCAL else Availability.CLOUD },
        )
        assertEquals(FolderStats(total = 6, read = 2, reading = 1, unread = 3, offline = 2), s)
        assertEquals(FolderMark.IN_PROGRESS, s.mark)
        assertEquals(3, s.count(LibraryFilter.TO_READ))
        assertEquals(2, s.count(LibraryFilter.OFFLINE))
    }

    @Test fun folderMarks() {
        assertEquals(FolderMark.ALL_READ, FolderStats(3, 3, 0, 0, 0).mark)
        assertEquals(FolderMark.IN_PROGRESS, FolderStats(3, 0, 1, 2, 0).mark)
        assertEquals(FolderMark.IN_PROGRESS, FolderStats(3, 1, 0, 2, 0).mark)
        assertEquals(FolderMark.NONE, FolderStats(3, 0, 0, 3, 0).mark)
        assertEquals(FolderMark.NONE, FolderStats.EMPTY.mark)
    }

    @Test fun foldersHideOnlyWhenTheFilterLeavesNothingInside() {
        val onlyRead = FolderStats(4, 4, 0, 0, 0)
        assertTrue(LibraryLogic.folderVisible(LibraryFilter.ALL, FolderStats.EMPTY))
        assertTrue(LibraryLogic.folderVisible(LibraryFilter.READ, onlyRead))
        assertFalse(LibraryLogic.folderVisible(LibraryFilter.TO_READ, onlyRead))
        assertFalse(LibraryLogic.folderVisible(LibraryFilter.OFFLINE, onlyRead))
    }

    @Test fun folderSummaryTexts() {
        assertEquals("30 cómics · 12 leídos", LibraryLogic.folderSummary(FolderStats(30, 12, 2, 16, 0)))
        assertEquals("30 cómics · 1 leído", LibraryLogic.folderSummary(FolderStats(30, 1, 0, 29, 0)))
        assertEquals("30 cómics · todos leídos", LibraryLogic.folderSummary(FolderStats(30, 30, 0, 0, 0)))
        assertEquals("1 cómic · leído", LibraryLogic.folderSummary(FolderStats(1, 1, 0, 0, 0)))
        assertEquals("30 cómics · 2 en curso", LibraryLogic.folderSummary(FolderStats(30, 0, 2, 28, 0)))
        assertEquals("30 cómics", LibraryLogic.folderSummary(FolderStats(30, 0, 0, 30, 0)))
        assertEquals("1.234 cómics", LibraryLogic.folderSummary(FolderStats(1234, 0, 0, 1234, 0)))
        assertEquals("Vacía", LibraryLogic.folderSummary(FolderStats.EMPTY))
    }

    @Test fun bookStatusFromProgress() {
        assertEquals(BookStatus.Unread, LibraryLogic.statusOf(null))
        assertEquals(BookStatus.Unread, LibraryLogic.statusOf(progress(0, 36)))
        assertEquals(BookStatus.Reading(10, 36), LibraryLogic.statusOf(progress(9, 36)))
        assertEquals(BookStatus.Finished, LibraryLogic.statusOf(progress(35, 36, finished = true)))
        assertEquals("No leído", LibraryLogic.statusText(BookStatus.Unread))
        assertEquals("Pág. 10 de 36", LibraryLogic.statusText(BookStatus.Reading(10, 36)))
        assertEquals("Leído", LibraryLogic.statusText(BookStatus.Finished))
        assertEquals(0.5f, BookStatus.Reading(18, 36).fraction, 0.0001f)
        assertEquals(0f, BookStatus.Reading(3, 0).fraction, 0f)
    }

    @Test fun gridColumnsFollowTheWindowWidthClass() {
        assertEquals(3, LibraryLogic.gridColumns(411, atLeastMedium = false, atLeastExpanded = false))
        assertEquals(5, LibraryLogic.gridColumns(720, atLeastMedium = true, atLeastExpanded = false))   // NXTPAPER en vertical
        assertEquals(4, LibraryLogic.gridColumns(640, atLeastMedium = true, atLeastExpanded = false))
        assertEquals(8, LibraryLogic.gridColumns(1100, atLeastMedium = true, atLeastExpanded = true))  // en horizontal
        assertEquals(7, LibraryLogic.gridColumns(900, atLeastMedium = true, atLeastExpanded = true))
    }
}
