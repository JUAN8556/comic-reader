package mx.jano.comics.reader

/** Textos del lector (español). La i18n llega después: aquí quedan juntos para moverlos a strings.xml de una vez. */
internal object ReaderTexts {
    // Páginas
    fun pageOf(page: Int, count: Int) = "Pág. $page de $count"
    fun pageDescription(page: Int, count: Int) = "Página $page de $count"
    fun spreadDescription(first: Int, second: Int, count: Int) = "Páginas $first y $second de $count"
    fun pageError(page: Int) = "No se pudo mostrar la página $page"
    fun spreadOf(spread: Int, count: Int) = "Pliego $spread de $count"
    const val RETRY = "Reintentar"
    const val CLOSE = "Cerrar"
    const val EMPTY = "Este archivo no tiene páginas de imagen."
    const val DIRECTION_LTR = "Lectura de izquierda a derecha"
    const val DIRECTION_RTL = "Manga: lectura de derecha a izquierda"
    const val LOADING = "Cargando página"

    // Ruta: descarga y apertura
    const val DOWNLOADING = "Descargando"
    const val PREPARING = "Preparando"
    const val OPENING = "Abriendo el cómic"
    const val CANCEL = "Cancelar"
    const val DOWNLOAD_FAILED = "No se pudo descargar el cómic"
    const val DOWNLOAD_CANCELLED = "Se canceló la descarga"
    const val OPEN_FAILED = "No se pudo abrir el cómic"
    const val OFFLINE_HINT = "Comprueba la conexión: los cómics que no están en la tablet se bajan de Drive."
    fun downloadDescription(percent: String) = "Descarga al $percent"

    // Fin de número
    const val NEXT = "Siguiente"
    const val READ = "Leer"
    const val BACK = "Volver"
    const val FOLDER_DONE = "Has terminado la carpeta"
    const val FOLDER_DONE_HINT = "No hay más números después de este en la carpeta."
    const val ISSUE_DONE = "Fin del número"
    const val NEXT_COVER = "Portada del siguiente número"
    const val BOOK_COVER = "Portada"

    // Superposición
    const val FIT = "Ajuste"
    const val FIT_WIDTH = "Ancho"
    const val FIT_HEIGHT = "Alto"
    const val FIT_BOTH = "Página entera"
    const val FIT_VERTICAL_NOTE = "En vertical continuo las páginas van a lo ancho."
    const val LAYOUT = "Páginas"
    const val LAYOUT_SINGLE = "Una página"
    const val LAYOUT_DOUBLE = "Doble en horizontal"
    const val LAYOUT_DOUBLE_NOTE = "Con la tablet en vertical se ve una página."
    const val LAYOUT_VERTICAL = "Vertical continuo"
    const val MANGA = "Manga"
    const val MANGA_YES = "Sí, en este libro"
    const val MANGA_NO = "No, en este libro"
    fun mangaInherit(rtl: Boolean) = "Heredar de la carpeta (ahora: ${if (rtl) "sí" else "no"})"
    fun mangaButton(rtl: Boolean) = "Manga: ${if (rtl) "sí" else "no"}"
    fun fitButton(label: String) = "$FIT: $label"
    fun layoutButton(label: String) = "$LAYOUT: $label"
    const val BACK_AGAIN = "Atrás otra vez para salir del libro"
    const val SELECTED = "elegido"
}
