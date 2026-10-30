package mx.jano.comics.ui

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mx.jano.comics.cache.Availability
import mx.jano.comics.library.ReadState
import mx.jano.comics.ui.fakes.FakeAppGraph
import mx.jano.comics.ui.library.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UiActionsTest {
    /** Al ir a segundo plano se copia el progreso a Drive solo si cambió desde la última copia, y solo con red. */
    @Test fun backupOnStopOnlyWhenChangedAndOnline() = runTest {
        val graph = FakeAppGraph()
        val prefs = UiPrefsStore(null)
        val actions = UiActions(graph, this, prefs)
        actions.onAppStopped()
        advanceUntilIdle()
        assertEquals(1, graph.progress.backups)
        assertNotNull(prefs.state.value.lastBackupAt)
        actions.onAppStopped()
        advanceUntilIdle()
        assertEquals(1, graph.progress.backups)
        graph.progress.update(graph.library.book("xmen-b2")!!, 5, 24)
        actions.onAppStopped()
        advanceUntilIdle()
        assertEquals(2, graph.progress.backups)

        val offline = FakeAppGraph(online = false)
        val offlinePrefs = UiPrefsStore(null)
        UiActions(offline, this, offlinePrefs).onAppStopped()
        advanceUntilIdle()
        assertEquals(0, offline.progress.backups)
        assertNull(offlinePrefs.state.value.lastBackupAt)
    }

    @Test fun markUnreadAlsoResetsThePage() = runTest {
        val graph = FakeAppGraph()
        val actions = UiActions(graph, this, UiPrefsStore(null))
        val reading = graph.library.book("xmen-b2")!!
        assertEquals(ReadState.READING, graph.progress.stateOf(reading))
        actions.markFinished(listOf(reading), finished = false)
        advanceUntilIdle()
        assertEquals(ReadState.UNREAD, graph.progress.stateOf(reading))
        assertEquals(0, graph.progress.get(reading.contentKey)!!.page)
    }

    @Test fun markAFolderAsRead() = runTest {
        val graph = FakeAppGraph()
        val actions = UiActions(graph, this, UiPrefsStore(null))
        val message = async(start = CoroutineStart.UNDISPATCHED) { actions.messages.first() }
        actions.markFinished(graph.library.booksUnder("asterix"), finished = true)
        advanceUntilIdle()
        assertTrue(graph.library.booksUnder("asterix").all { graph.progress.stateOf(it) == ReadState.FINISHED })
        assertEquals("5 cómics marcados como leído", message.await())
    }

    @Test fun evictUnpinsFirstAndSkipsWhatIsOnlyInTheCloud() = runTest {
        val graph = FakeAppGraph(local = setOf("xmen-b3"), pinned = setOf("xmen-b4"))
        val actions = UiActions(graph, this, UiPrefsStore(null))
        val books = listOf("xmen-b3", "xmen-b4", "xmen-b5").map { graph.library.book(it)!! }
        val message = async(start = CoroutineStart.UNDISPATCHED) { actions.messages.first() }
        actions.evict(books)
        advanceUntilIdle()
        assertTrue(books.all { graph.cache.availability(it) == Availability.CLOUD })
        assertFalse(graph.pins.isPinned(books[1]))
        assertEquals("Borrados de la tablet: 2 cómics", message.await())
    }

    @Test fun rtlOnTheRootIsTheGlobalSetting() = runTest {
        val graph = FakeAppGraph()
        val actions = UiActions(graph, this, UiPrefsStore(null))
        actions.setRtl("Manga/Berserk", true)
        advanceUntilIdle()
        assertTrue(graph.settings.rtlFor("Manga/Berserk/Berserk v1.cbz"))
        assertFalse(graph.settings.rtlFor("Europeo/Astérix/Astérix 1.cbz"))
        actions.setRtl("", true)
        advanceUntilIdle()
        assertTrue(graph.settings.reader.value.rtlGlobal)
        assertTrue(graph.settings.rtlFor("Europeo/Astérix/Astérix 1.cbz"))
    }

    @Test fun theFilterIsRemembered() = runTest {
        val prefs = UiPrefsStore(null)
        val actions = UiActions(FakeAppGraph(), this, prefs)
        actions.setFilter(LibraryFilter.OFFLINE)
        advanceUntilIdle()
        assertEquals(LibraryFilter.OFFLINE, prefs.state.value.libraryFilter)
    }
}
