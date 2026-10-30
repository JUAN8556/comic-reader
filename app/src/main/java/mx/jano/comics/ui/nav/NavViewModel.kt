package mx.jano.comics.ui.nav

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * La pila de pantallas de la Activity. Sobrevive a giros (ViewModel) y a la muerte del proceso (SavedStateHandle).
 * [rootId] y [parentOf] los pone la raíz de la interfaz en cada composición (dependen de ajustes y del índice).
 */
class NavViewModel(private val saved: SavedStateHandle) : ViewModel(), Navigator {
    private val _stack = MutableStateFlow(NavStack.decode(saved.get<ArrayList<String>>(KEY_STACK)))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    @Volatile var rootId: String? = null
    @Volatile var parentOf: (String) -> String? = { null }

    /** Raíz para la que ya se lanzaron las tareas de arranque (token, escaneo, índice): una vez por proceso. */
    var startedFor: String? = null

    /** Pone la primera pantalla si la pila está vacía (primer arranque o estado perdido). */
    fun ensureStarted(initial: Screen) {
        if (_stack.value.isEmpty()) set(listOf(initial))
    }

    val canGoBack: Boolean get() = NavStack.canGoBack(_stack.value, rootId)

    override fun open(screen: Screen) = when (screen) {
        Screen.Now, Screen.Search, Screen.Settings, Screen.Diagnostico -> set(NavStack.singleTop(_stack.value, screen))
        else -> set(NavStack.push(_stack.value, screen))
    }

    override fun openFolder(folderId: String) = set(NavStack.push(_stack.value, Screen.Library(folderId)))

    override fun goToAncestor(folderId: String) = set(NavStack.goToAncestor(_stack.value, folderId))

    override fun openBook(bookId: String) = set(NavStack.push(_stack.value, Screen.Reader(bookId)))

    override fun replaceTop(screen: Screen) = set(NavStack.replaceTop(_stack.value, screen))

    override fun resetTo(screen: Screen) = set(listOf(screen))

    override fun back() {
        NavStack.back(_stack.value, rootId, parentOf)?.let(::set)
    }

    private fun set(list: List<Screen>) {
        _stack.value = list
        saved[KEY_STACK] = NavStack.encode(list)
    }

    private companion object {
        const val KEY_STACK = "nav_stack"
    }
}
