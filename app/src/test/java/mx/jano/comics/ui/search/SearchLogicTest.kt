package mx.jano.comics.ui.search

import mx.jano.comics.ui.FallbackNatural
import mx.jano.comics.ui.fakes.FakeData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchLogicTest {
    private val corpus = SearchCorpus(FakeData.index())
    private fun search(q: String, max: Int = SearchLogic.MAX_BOOKS) = SearchLogic.search(corpus, q, FallbackNatural, max)

    @Test fun ignoresAccentsAndCase() {
        val r = search("ASTERIX")
        assertEquals(listOf("Astérix"), r.folders.map { it.name })
        assertEquals(5, r.bookCount)
        assertEquals(listOf("Europeo/Astérix"), r.groups.map { it.folderPath })
    }

    @Test fun everyWordMustAppear() {
        val r = search("x-men 12")
        assertEquals(listOf("Uncanny X-Men 12.cbr"), r.groups.flatMap { it.books }.map { it.name })
        assertTrue(r.folders.isEmpty())
        assertTrue(search("berserk asterix").isEmpty)
    }

    @Test fun booksAreGroupedByFolderInNaturalOrder() {
        val r = search("uncanny")
        assertEquals(1, r.groups.size)
        val names = r.groups[0].books.map { it.name }
        assertEquals("Uncanny X-Men 1.cbr", names[0])
        assertEquals("Uncanny X-Men 2.cbr", names[1])
        assertEquals("Uncanny X-Men 12.cbr", names.last())
        assertEquals("Uncanny X-Men", r.groups[0].folderName)
    }

    @Test fun matchesFoldersAndBooksTogether() {
        val r = search("man")
        // Carpetas: Manga, Manhua, Manhwa (la raíz nunca sale).
        assertEquals(listOf("Manga", "Manhua", "Manhwa"), r.folders.map { it.name })
        assertTrue(r.groups.isEmpty())
    }

    @Test fun blankQueryGivesNothing() {
        assertTrue(search("").isEmpty)
        assertTrue(search("  ·  ").isEmpty)
        assertFalse(search("Mafalda").isEmpty)
    }

    @Test fun manyResultsAreTruncatedButCounted() {
        val r = search("uncanny", max = 5)
        assertEquals(12, r.bookCount)
        assertEquals(5, r.shownBooks)
        assertTrue(r.truncated)
        assertEquals(5, r.groups.sumOf { it.books.size })
    }

    @Test fun theRootIsNeverAFolderResult() {
        assertTrue(search("comics").folders.isEmpty())
    }
}
