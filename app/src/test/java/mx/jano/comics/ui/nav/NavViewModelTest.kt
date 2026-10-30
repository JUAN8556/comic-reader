package mx.jano.comics.ui.nav

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavViewModelTest {
    private val parents = mapOf("A" to "R", "AB" to "A")

    private fun vm(saved: SavedStateHandle = SavedStateHandle()) = NavViewModel(saved).apply {
        rootId = "R"
        parentOf = { parents[it] }
    }

    @Test fun startsOnceAndNavigates() {
        val nav = vm()
        nav.ensureStarted(Screen.Library("R"))
        nav.ensureStarted(Screen.Onboarding())
        assertEquals(listOf(Screen.Library("R")), nav.stack.value)
        assertFalse(nav.canGoBack)
        nav.openFolder("A")
        nav.openFolder("AB")
        nav.openBook("b1")
        assertTrue(nav.canGoBack)
        nav.replaceTop(Screen.Reader("b2"))
        assertEquals(Screen.Reader("b2"), nav.stack.value.last())
        nav.back()
        nav.back()
        assertEquals(listOf(Screen.Library("R"), Screen.Library("A")), nav.stack.value)
    }

    @Test fun topLevelScreensAreSingleTop() {
        val nav = vm()
        nav.ensureStarted(Screen.Library("R"))
        nav.open(Screen.Settings)
        nav.open(Screen.Diagnostico)
        nav.open(Screen.Settings)
        assertEquals(listOf(Screen.Library("R"), Screen.Settings), nav.stack.value)
        nav.open(Screen.Series("A"))
        nav.open(Screen.Series("A"))
        assertEquals(3, nav.stack.value.size)
    }

    @Test fun theStackIsRestoredFromTheSavedState() {
        val saved = SavedStateHandle()
        val first = vm(saved)
        first.ensureStarted(Screen.Library("R"))
        first.openFolder("A")
        first.open(Screen.Search)
        val restored = vm(saved)
        assertEquals(first.stack.value, restored.stack.value)
        restored.ensureStarted(Screen.Onboarding())
        assertEquals(3, restored.stack.value.size)
    }

    @Test fun backAtTheRootDoesNothing() {
        val nav = vm()
        nav.resetTo(Screen.Library("R"))
        nav.back()
        assertEquals(listOf(Screen.Library("R")), nav.stack.value)
    }
}
