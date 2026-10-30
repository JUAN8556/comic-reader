package mx.jano.comics.ui.nav

/**
 * Operaciones puras sobre la pila de pantallas (la última es la visible). Inmutables: devuelven la pila nueva.
 *
 * «Atrás» en la biblioteca SUBE UN NIVEL: desde una carpeta vuelve a su carpeta madre aunque se haya llegado a ella
 * desde la búsqueda; en la raíz sin nada debajo no hay «atrás» y el sistema sale de la app con su animación.
 */
object NavStack {
    fun push(stack: List<Screen>, screen: Screen): List<Screen> =
        if (stack.lastOrNull() == screen) stack else stack + screen

    fun replaceTop(stack: List<Screen>, screen: Screen): List<Screen> =
        if (stack.isEmpty()) listOf(screen) else stack.dropLast(1) + screen

    /** Vuelve a la última aparición de [screen] (quita lo de encima), o null si no está en la pila. */
    fun popTo(stack: List<Screen>, screen: Screen): List<Screen>? {
        val i = stack.lastIndexOf(screen)
        return if (i < 0) null else stack.subList(0, i + 1).toList()
    }

    /** Pantallas de primer nivel (Leyendo ahora, Buscar, Ajustes): si ya están en la pila se vuelve a ellas. */
    fun singleTop(stack: List<Screen>, screen: Screen): List<Screen> = popTo(stack, screen) ?: push(stack, screen)

    /** Migas de pan: a una carpeta antepasada de la actual. */
    fun goToAncestor(stack: List<Screen>, folderId: String): List<Screen> {
        val target = Screen.Library(folderId)
        return popTo(stack, target) ?: replaceTop(stack, target)
    }

    fun canGoBack(stack: List<Screen>, rootId: String?): Boolean {
        if (stack.size > 1) return true
        val top = stack.lastOrNull()
        return top is Screen.Library && rootId != null && top.folderId != rootId
    }

    /**
     * La pila tras «atrás», o null si no hay a dónde volver (salir de la app).
     * [parentOf] da la carpeta madre (null si no se conoce: entonces se sube a la raíz).
     */
    fun back(stack: List<Screen>, rootId: String?, parentOf: (String) -> String?): List<Screen>? {
        val top = stack.lastOrNull() ?: return null
        if (top is Screen.Library && rootId != null && top.folderId != rootId) {
            val parent = parentOf(top.folderId)?.takeIf { it != top.folderId } ?: rootId
            val below = stack.getOrNull(stack.size - 2)
            return when {
                below == Screen.Library(parent) -> stack.dropLast(1)
                parent == rootId && below != null -> stack.dropLast(1)
                else -> replaceTop(stack, Screen.Library(parent))
            }
        }
        return if (stack.size > 1) stack.dropLast(1) else null
    }

    fun encode(stack: List<Screen>): ArrayList<String> = ArrayList(stack.map { it.key })

    fun decode(keys: List<String>?): List<Screen> = keys.orEmpty().mapNotNull(Screen::decode)
}
