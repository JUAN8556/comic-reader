package mx.jano.comics.reader

/** Textos del lector (español). La i18n llega después: aquí quedan juntos para moverlos a strings.xml de una vez. */
internal object ReaderTexts {
    fun pageOf(page: Int, count: Int) = "Pág. $page de $count"
    fun pageDescription(page: Int, count: Int) = "Página $page de $count"
    fun pageError(page: Int) = "No se pudo mostrar la página $page"
    const val RETRY = "Reintentar"
    const val CLOSE = "Cerrar"
    const val EMPTY = "Este archivo no tiene páginas de imagen."
    const val DIRECTION_LTR = "Lectura de izquierda a derecha"
    const val DIRECTION_RTL = "Manga: lectura de derecha a izquierda"
    const val LOADING = "Cargando página"
}
