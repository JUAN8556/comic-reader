package mx.jano.comics.ui.nav

/**
 * Pantallas de la app. La navegación es una pila de estas (sin Navigation Compose): [NavStack] la opera y
 * [NavViewModel] la guarda en el SavedStateHandle por su [key].
 */
sealed interface Screen {
    /** Clave estable: sirve para guardar la pila y el estado de cada pantalla (scroll de la rejilla). */
    val key: String

    /** Conectar Drive y elegir la carpeta raíz. [changeRoot]: llegado desde Ajustes para cambiarla. */
    data class Onboarding(val changeRoot: Boolean = false) : Screen {
        override val key: String get() = if (changeRoot) "onb:change" else "onb"
    }

    data class Library(val folderId: String) : Screen {
        override val key: String get() = "lib:$folderId"
    }

    data object Now : Screen {
        override val key: String get() = "now"
    }

    data object Search : Screen {
        override val key: String get() = "search"
    }

    data object Settings : Screen {
        override val key: String get() = "settings"
    }

    data object Diagnostico : Screen {
        override val key: String get() = "diag"
    }

    data class Reader(val bookId: String) : Screen {
        override val key: String get() = "reader:$bookId"
    }

    /** Ficha de la serie de una carpeta. */
    data class Series(val folderId: String) : Screen {
        override val key: String get() = "series:$folderId"
    }

    companion object {
        fun decode(key: String): Screen? {
            val kind = key.substringBefore(':')
            val arg = key.substringAfter(':', "")
            return when (kind) {
                "onb" -> Onboarding(changeRoot = arg == "change")
                "lib" -> arg.takeIf { it.isNotEmpty() }?.let(::Library)
                "now" -> Now
                "search" -> Search
                "settings" -> Settings
                "diag" -> Diagnostico
                "reader" -> arg.takeIf { it.isNotEmpty() }?.let(::Reader)
                "series" -> arg.takeIf { it.isNotEmpty() }?.let(::Series)
                else -> null
            }
        }
    }
}
