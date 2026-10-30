package mx.jano.comics.ui.library

import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.ui.fakes.FakeData
import mx.jano.comics.ui.fakes.FakeLibraryRepository
import mx.jano.comics.ui.fakes.FakeProgressStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryContentBuilderTest {
    private val index = FakeData.index()
    private val library = FakeLibraryRepository(index)
    private val progress = FakeData.progress(index)
    private val progressStore = FakeProgressStore(progress)
    private val byKey = index.books.associateBy { it.contentKey }

    private fun build(folderId: String, filter: LibraryFilter = LibraryFilter.ALL, local: Set<String> = emptySet()) =
        LibraryContentBuilder.build(
            library = library,
            folderId = folderId,
            isRoot = folderId == FakeData.ROOT,
            progress = progress,
            filter = filter,
            availabilityOf = { if (it.id in local) Availability.LOCAL else Availability.CLOUD },
            hasCover = { false },
            recent = { progressStore.recent(10) },
            bookByKey = byKey::get,
        )

    @Test fun theRootShowsTheSixCategoriesAsShelvesInNaturalOrder() {
        val c = build(FakeData.ROOT)
        assertEquals(
            listOf("Americano", "Europeo", "Latinoamericano", "Manga", "Manhua", "Manhwa"),
            c.folders.map { it.folder.name },
        )
        assertTrue(c.books.isEmpty())
        assertEquals(listOf("Cómics"), c.crumbs.map { it.name })
        val americano = c.folders.first()
        assertEquals(FolderStats(total = 12, read = 1, reading = 1, unread = 10, offline = 0), americano.stats)
        assertEquals(3, americano.coverBooks.size)
    }

    @Test fun continueRowOnlyAtTheRootAndMostRecentFirst() {
        assertEquals(listOf("berserk-b1", "xmen-b2"), build(FakeData.ROOT).continueReading.map { it.book.id })
        assertTrue(build("xmen").continueReading.isEmpty())
    }

    @Test fun aSeriesFolderListsItsBooksWithStatusAndCrumbs() {
        val c = build("xmen")
        assertEquals(12, c.books.size)
        assertEquals("Uncanny X-Men 1.cbr", c.books[0].book.name)
        assertEquals("Uncanny X-Men 2.cbr", c.books[1].book.name)
        assertEquals("Uncanny X-Men 10.cbr", c.books[9].book.name)
        assertEquals(BookStatus.Finished, c.books[0].status)
        assertEquals(BookStatus.Reading(10, 36), c.books[1].status)
        assertEquals(listOf("Cómics", "Americano", "Marvel", "Uncanny X-Men"), c.crumbs.map { it.name })
        assertTrue(c.books.none { it.displayName.endsWith(".cbr") })
    }

    @Test fun filtersHideFoldersWithNothingMatchingAndKeepTheTotal() {
        val reading = build(FakeData.ROOT, LibraryFilter.READING)
        assertEquals(listOf("Americano", "Manga"), reading.folders.map { it.folder.name })
        val read = build(FakeData.ROOT, LibraryFilter.READ)
        assertEquals(listOf("Americano", "Europeo"), read.folders.map { it.folder.name })
        val nothing = build("mafalda", LibraryFilter.READ)
        assertTrue(nothing.isEmpty)
        assertEquals(3, nothing.totalChildren)
    }

    @Test fun offlineFilterKeepsOnlyWhatIsOnTheTablet() {
        val c = build("xmen", LibraryFilter.OFFLINE, local = setOf("xmen-b3", "xmen-b7"))
        assertEquals(listOf("xmen-b3", "xmen-b7"), c.books.map { it.book.id })
        val root = build(FakeData.ROOT, LibraryFilter.OFFLINE, local = setOf("xmen-b3"))
        assertEquals(listOf("Americano"), root.folders.map { it.folder.name })
    }

    @Test fun emptyCategoriesStillShowWithEverything() {
        val manhwa = build(FakeData.ROOT).folders.first { it.folder.name == "Manhwa" }
        assertEquals(FolderStats.EMPTY, manhwa.stats)
        assertTrue(manhwa.coverBooks.isEmpty())
        assertTrue(build("manhwa").isEmpty)
    }

    @Test fun coverStackPrefersBooksThatAlreadyHaveACover() {
        val books = (1..20).map { Book("b$it", "b$it.cbz", "f", "f/b$it.cbz", 1) }
        val withCover = setOf("b5", "b9")
        val picked = LibraryContentBuilder.pickCoverBooks(books, { it.id in withCover })
        assertEquals(listOf("b5", "b9", "b1"), picked.map { it.id })
        assertEquals(listOf("b1", "b2", "b3"), LibraryContentBuilder.pickCoverBooks(books, { false }).map { it.id })
        assertEquals(listOf("b1"), LibraryContentBuilder.pickCoverBooks(books.take(1), { false }).map { it.id })
        // Solo se miran los 12 primeros: una portada más allá no cuenta.
        assertEquals(listOf("b1", "b2", "b3"), LibraryContentBuilder.pickCoverBooks(books, { it.id == "b15" }).map { it.id })
    }

    @Test fun backdropFallsBackToTheFirstBook() {
        assertEquals("xmen-b1", build("xmen").backdrop?.id)
        assertNull(build("manhwa").backdrop)
    }
}
