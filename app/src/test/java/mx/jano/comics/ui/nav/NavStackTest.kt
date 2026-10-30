package mx.jano.comics.ui.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    private val root = "R"
    // R ─ A ─ AB ─ ABC ; R ─ M
    private val parents = mapOf("A" to "R", "AB" to "A", "ABC" to "AB", "M" to "R")
    private val parentOf: (String) -> String? = { parents[it] }
    private fun lib(id: String) = Screen.Library(id)

    @Test fun pushIgnoresTheSameScreenTwice() {
        val s = NavStack.push(listOf(lib(root)), lib("A"))
        assertEquals(s, NavStack.push(s, lib("A")))
        assertEquals(listOf(lib(root), lib("A"), Screen.Search), NavStack.push(s, Screen.Search))
    }

    @Test fun backFromAChildFolderPopsToItsParent() {
        val s = listOf(lib(root), lib("A"), lib("AB"))
        assertEquals(listOf(lib(root), lib("A")), NavStack.back(s, root, parentOf))
    }

    @Test fun backClimbsOneLevelEvenWhenTheFolderCameFromSearch() {
        val s = listOf(lib(root), Screen.Search, lib("ABC"))
        val up1 = NavStack.back(s, root, parentOf)
        assertEquals(listOf(lib(root), Screen.Search, lib("AB")), up1)
        val up2 = NavStack.back(up1!!, root, parentOf)
        assertEquals(listOf(lib(root), Screen.Search, lib("A")), up2)
        // A cuelga de la raíz: atrás vuelve a lo que había debajo (la búsqueda), no a otra raíz.
        assertEquals(listOf(lib(root), Screen.Search), NavStack.back(up2!!, root, parentOf))
    }

    @Test fun backFromALoneSubfolderGoesToTheRoot() {
        assertEquals(listOf(lib(root)), NavStack.back(listOf(lib("A")), root, parentOf))
    }

    @Test fun backFromAnUnknownFolderGoesToTheRoot() {
        assertEquals(listOf(lib(root)), NavStack.back(listOf(lib("?")), root, parentOf))
    }

    @Test fun backAtTheRootMeansLeaveTheApp() {
        assertNull(NavStack.back(listOf(lib(root)), root, parentOf))
        assertFalse(NavStack.canGoBack(listOf(lib(root)), root))
        assertNull(NavStack.back(emptyList(), root, parentOf))
    }

    @Test fun canGoBackInsideTheLibraryOrWithSomethingBelow() {
        assertTrue(NavStack.canGoBack(listOf(lib("A")), root))
        assertTrue(NavStack.canGoBack(listOf(lib(root), Screen.Settings), root))
        assertFalse(NavStack.canGoBack(listOf(Screen.Onboarding()), root))
        assertFalse(NavStack.canGoBack(listOf(lib("A")), null))
    }

    @Test fun readerAndOtherScreensJustPop() {
        val s = listOf(lib(root), lib("A"), Screen.Reader("b1"))
        assertEquals(listOf(lib(root), lib("A")), NavStack.back(s, root, parentOf))
        assertEquals(listOf(lib(root)), NavStack.back(listOf(lib(root), Screen.Now), root, parentOf))
    }

    @Test fun singleTopReturnsToAnExistingScreen() {
        val s = listOf(lib(root), Screen.Search, lib("A"), Screen.Reader("b"))
        assertEquals(listOf(lib(root), Screen.Search), NavStack.singleTop(s, Screen.Search))
        assertEquals(s + Screen.Settings, NavStack.singleTop(s, Screen.Settings))
    }

    @Test fun breadcrumbsGoBackToAnAncestorOrReplaceTheTop() {
        val s = listOf(lib(root), lib("A"), lib("AB"), lib("ABC"))
        assertEquals(listOf(lib(root), lib("A")), NavStack.goToAncestor(s, "A"))
        val fromSearch = listOf(lib(root), Screen.Search, lib("ABC"))
        assertEquals(listOf(lib(root), Screen.Search, lib("A")), NavStack.goToAncestor(fromSearch, "A"))
    }

    @Test fun replaceTopSwapsTheReaderForTheNextBook() {
        val s = listOf(lib(root), Screen.Reader("b1"))
        assertEquals(listOf(lib(root), Screen.Reader("b2")), NavStack.replaceTop(s, Screen.Reader("b2")))
        assertEquals(listOf(Screen.Now), NavStack.replaceTop(emptyList(), Screen.Now))
    }

    @Test fun theStackSurvivesEncodeAndDecode() {
        val s = listOf(
            Screen.Onboarding(), Screen.Onboarding(changeRoot = true), lib("1AbC-_x"), Screen.Now, Screen.Search,
            Screen.Settings, Screen.Diagnostico, Screen.Reader("b-9"), Screen.Series("1AbC-_x"),
        )
        assertEquals(s, NavStack.decode(NavStack.encode(s)))
    }

    @Test fun decodeSkipsWhatItDoesNotUnderstand() {
        assertEquals(listOf(lib("A")), NavStack.decode(listOf("lib:A", "lib:", "reader:", "otra:cosa", "")))
        assertEquals(emptyList<Screen>(), NavStack.decode(null))
    }
}
