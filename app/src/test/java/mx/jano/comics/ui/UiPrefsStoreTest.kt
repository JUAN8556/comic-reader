package mx.jano.comics.ui

import kotlinx.coroutines.test.runTest
import mx.jano.comics.ui.library.LibraryFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UiPrefsStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun savesAndReadsBack() = runTest {
        val file = File(tmp.root, "ui-prefs.json")
        val store = UiPrefsStore(file)
        assertEquals(UiPrefs(), store.state.value)
        val ink = mapOf("secure/nxtpaper_ink_style_state" to "2", "system/color_mode_value" to null)
        store.update { it.copy(inkPaperValues = ink, libraryFilter = LibraryFilter.READING, lastBackupAt = 42L) }
        assertTrue(file.isFile)
        val again = UiPrefsStore(file)
        assertEquals(UiPrefs(ink, LibraryFilter.READING, 42L), again.state.value)
    }

    @Test fun leavesNoTemporaryFileBehind() = runTest {
        val file = File(tmp.root, "ui-prefs.json")
        val store = UiPrefsStore(file)
        repeat(3) { i -> store.update { it.copy(lastBackupAt = i.toLong()) } }
        assertEquals(listOf("ui-prefs.json"), tmp.root.list()!!.toList())
        assertEquals(2L, UiPrefsStore(file).state.value.lastBackupAt)
    }

    @Test fun aBrokenFileGivesDefaultsAndIsReplacedOnTheNextWrite() = runTest {
        val file = File(tmp.root, "ui-prefs.json").apply { writeText("{ esto no es json") }
        val store = UiPrefsStore(file)
        assertEquals(UiPrefs(), store.state.value)
        store.update { it.copy(libraryFilter = LibraryFilter.OFFLINE) }
        assertEquals(LibraryFilter.OFFLINE, UiPrefsStore(file).state.value.libraryFilter)
    }

    @Test fun unknownFieldsFromANewerVersionAreIgnored() {
        val file = File(tmp.root, "ui-prefs.json").apply { writeText("""{"libraryFilter":"READ","futuro":true}""") }
        assertEquals(LibraryFilter.READ, UiPrefsStore(file).state.value.libraryFilter)
    }

    @Test fun anUnchangedUpdateDoesNotWrite() = runTest {
        val file = File(tmp.root, "ui-prefs.json")
        val store = UiPrefsStore(file)
        store.update { it }
        assertFalse(file.exists())
    }

    @Test fun inMemoryStoreWorksWithoutAFile() = runTest {
        val store = UiPrefsStore(null)
        store.update { it.copy(libraryFilter = LibraryFilter.TO_READ) }
        assertEquals(LibraryFilter.TO_READ, store.state.value.libraryFilter)
    }
}
