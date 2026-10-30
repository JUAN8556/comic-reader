package mx.jano.comics.ui

/**
 * Textos de la interfaz (español). La i18n llega después: aquí quedan juntos para moverlos a strings.xml de una vez.
 * Ningún estado se comunica solo con color: cada marca lleva su texto o su descripción para TalkBack.
 */
object UiStrings {
    // Generales
    const val APP_TITLE = "Cómics"
    const val BACK = "Atrás"
    const val CLOSE = "Cerrar"
    const val CANCEL = "Cancelar"
    const val ACCEPT = "Aceptar"
    const val UNDERSTOOD = "Entendido"
    const val RETRY = "Reintentar"
    const val SEARCH = "Buscar"
    const val SETTINGS = "Ajustes"
    const val NOW_READING = "Leyendo ahora"
    const val MY_DRIVE = "Mi unidad"
    const val LOADING = "Cargando…"
    const val YES = "Sí"
    const val NO = "No"

    // Biblioteca
    const val LIB_CONTINUE = "Continuar"
    const val LIB_EMPTY_INDEX = "La biblioteca todavía no está escaneada."
    const val LIB_SCAN_NOW = "Escanear ahora"
    const val LIB_EMPTY_FOLDER = "Esta carpeta está vacía."
    const val LIB_EMPTY_FILTER = "Nada aquí con este filtro."
    const val LIB_SHOW_ALL = "Ver todo"
    const val LIB_FOLDER_GONE = "Esta carpeta ya no está en la biblioteca."
    const val LIB_GO_ROOT = "Ir al inicio"
    const val LIB_PULL_HINT = "Tira hacia abajo para volver a escanear"
    fun libScanning(folders: Int, books: Int) = "Escaneando Drive… ${UiFormat.count(folders)} carpetas · ${UiFormat.comics(books)}"
    fun libScanDone(folders: Int, books: Int, ms: Long) =
        "Biblioteca al día: ${UiFormat.count(folders)} carpetas · ${UiFormat.comics(books)} (${UiFormat.seconds(ms)})"
    fun libScanError(message: String) = "No se pudo escanear: $message"
    const val LIB_OFFLINE_BANNER = "Sin conexión: puedes leer lo que ya está en la tablet."
    const val LIB_OFFLINE_DIALOG_TITLE = "Sin conexión"
    fun libOfflineDialogBody(name: String) =
        "«$name» no está en la tablet. Cuando vuelva la conexión se descargará al abrirlo."

    // Filtros
    const val FILTER = "Filtro"
    const val FILTER_ALL = "Todos"
    const val FILTER_TO_READ = "Por leer"
    const val FILTER_READING = "Leyendo"
    const val FILTER_READ = "Leídos"
    const val FILTER_OFFLINE = "Sin conexión"

    // Estado de un libro
    const val STATUS_UNREAD = "No leído"
    const val STATUS_READ = "Leído"
    fun statusPage(page: Int, count: Int) = if (count > 0) "Pág. $page de $count" else "Pág. $page"

    // Resumen de una carpeta
    const val FOLDER_EMPTY = "Vacía"
    const val FOLDER_ALL_READ_ONE = "leído"
    const val FOLDER_ALL_READ = "todos leídos"
    fun folderRead(n: Int) = if (n == 1) "1 leído" else "${UiFormat.count(n)} leídos"
    fun folderInProgress(n: Int) = "${UiFormat.count(n)} en curso"
    const val MARK_ALL_READ = "Todo leído"
    const val MARK_IN_PROGRESS = "En curso"

    // Disponibilidad
    const val AVAIL_CLOUD = "En la nube"
    const val AVAIL_CLOUD_OFFLINE = "No disponible sin conexión"
    fun availDownloading(percent: Int?) = if (percent != null) "Descargando $percent %" else "Descargando"
    const val AVAIL_LOCAL = "En la tablet"
    const val AVAIL_PINNED = "Fijado para leer sin conexión"

    // Menú de pulsación larga
    const val MENU_OPTIONS = "Opciones"
    const val MENU_MARK_READ = "Marcar como leído"
    const val MENU_MARK_UNREAD = "Marcar como no leído"
    const val MENU_MARK_ALL_READ = "Marcar todo como leído"
    const val MENU_MARK_ALL_UNREAD = "Marcar todo como no leído"
    const val MENU_PIN = "Fijar para leer sin conexión"
    fun menuPinFolder(n: Int) = "Fijar la serie (${UiFormat.comics(n)})"
    const val MENU_UNPIN = "Quitar fijado"
    const val MENU_DELETE_LOCAL = "Borrar de la tablet"
    fun menuDeleteLocalFolder(n: Int) = "Borrar de la tablet (${UiFormat.count(n)} descargados)"
    fun menuRtlNow(rtl: Boolean) = "Sentido de lectura ahora: " + if (rtl) "manga (der. → izq.)" else "occidental (izq. → der.)"
    const val MENU_RTL_ON = "Manga: de derecha a izquierda"
    const val MENU_RTL_OFF = "Occidental: de izquierda a derecha"
    const val MENU_RTL_INHERIT = "Heredar de la carpeta o del ajuste global"
    const val MENU_SERIES = "Ver ficha de la serie"

    // Mensajes de las acciones
    fun msgMarked(n: Int, finished: Boolean) =
        (if (n == 1) "1 cómic marcado como " else "${UiFormat.count(n)} cómics marcados como ") + if (finished) "leído" else "no leído"
    fun msgPinned(n: Int, bytes: Long) =
        "Fijado: ${UiFormat.comics(n)}" + if (bytes > 0) ". Falta descargar ${UiFormat.bytes(bytes)}" else ", ya en la tablet"
    fun msgUnpinned(n: Int) = "Quitado el fijado de ${UiFormat.comics(n)}"
    fun msgEvicted(n: Int) = "Borrados de la tablet: ${UiFormat.comics(n)}"
    fun msgRtl(rtl: Boolean?) = when (rtl) {
        true -> "Se leerá como manga (de derecha a izquierda)"
        false -> "Se leerá de izquierda a derecha"
        null -> "Sentido de lectura heredado"
    }
    fun msgError(what: String, message: String?) = "$what: " + (message ?: "error desconocido")
    const val ERR_MARK = "No se pudo marcar"
    const val ERR_PIN = "No se pudo fijar"
    const val ERR_UNPIN = "No se pudo quitar el fijado"
    const val ERR_EVICT = "No se pudo borrar"
    const val ERR_RTL = "No se pudo cambiar el sentido de lectura"
    const val ERR_SCAN = "No se pudo escanear"

    // Leyendo ahora
    const val NOW_CONTINUE = "Continuar leyendo"
    const val NOW_NEXT = "Siguientes"
    const val NOW_EMPTY = "Todavía no has empezado ningún cómic. Lo que vayas leyendo aparecerá aquí."
    fun nowNextOf(folderName: String) = "Siguiente de «$folderName»"
    fun nowOpened(relative: String) = "Abierto $relative"

    // Ficha de serie
    const val SERIES_TITLE = "Ficha de la serie"
    const val SERIES_NONE = "Sin ficha todavía"
    const val SERIES_NONE_HELP =
        "La ficha sale de Cómics/.indice/series.jsonl, que genera la migración. Cuando exista, aparecerá aquí."
    const val SERIES_LOW_CONFIDENCE = "Ficha con confianza baja: puede contener errores."
    const val SERIES_ORIGINAL = "Título original"
    const val SERIES_TYPE = "Tipo"
    const val SERIES_CATEGORY = "Categoría"
    const val SERIES_COUNTRY = "País"
    const val SERIES_SCRIPT = "Guion"
    const val SERIES_ART = "Dibujo"
    const val SERIES_PUBLISHER_ORIGINAL = "Editorial original"
    const val SERIES_PUBLISHER_EDITION = "Edición"
    const val SERIES_LANGUAGE = "Idioma de la edición"
    const val SERIES_YEARS = "Años"
    const val SERIES_GENRES = "Géneros"
    const val SERIES_STATUS = "Estado"
    const val SERIES_ISSUES = "Números"
    fun seriesIssues(inFolder: Int?, total: Int?) = when {
        inFolder != null && total != null -> "$inFolder en la carpeta de $total publicados"
        total != null -> "$total publicados"
        inFolder != null -> "$inFolder en la carpeta"
        else -> ""
    }
    const val SERIES_SYNOPSIS = "Sinopsis"
    const val SERIES_SOURCES = "Fuentes"
    const val SERIES_NOTES = "Notas"
    fun seriesMissing(ranges: String) = "Faltan los números $ranges"
    const val SERIES_COMPLETE = "No falta ningún número"

    // Búsqueda
    const val SEARCH_HINT = "Buscar por nombre de carpeta o de cómic"
    const val SEARCH_CLEAR = "Borrar la búsqueda"
    const val SEARCH_EMPTY_QUERY = "Escribe para buscar en toda la biblioteca (sin importar acentos ni mayúsculas)."
    const val SEARCH_NO_RESULTS = "Nada coincide con la búsqueda."
    const val SEARCH_FOLDERS = "Carpetas"
    fun searchTruncated(shown: Int, total: Int) = "Se muestran $shown de $total cómics: afina la búsqueda."
    const val SEARCH_NO_INDEX = "La biblioteca aún no está escaneada."

    // Ajustes
    const val SET_APPEARANCE = "Apariencia"
    const val SET_THEME_COVER = "Cover oscuro"
    const val SET_THEME_HC = "Alto contraste"
    const val SET_THEME_AUTO = "Automático (NXTPAPER)"
    const val SET_THEME_HELP =
        "Alto contraste: blanco sobre negro, sin difuminados ni animaciones, pensado para el modo Ink Paper."
    fun setThemeNow(highContrast: Boolean) = "Ahora se ve: " + if (highContrast) "alto contraste" else "Cover oscuro"
    fun setDetected(values: String) = "Modo detectado: $values"
    const val SET_MARK_INK = "Este es el modo Ink Paper"
    const val SET_FORGET_INK = "Olvidar"
    fun setInkSaved(values: String) = "Ink Paper guardado: $values"
    const val SET_INK_NOT_SAVED =
        "Pon la tablet en Ink Paper y pulsa «Este es el modo Ink Paper»: desde entonces se aplicará el alto contraste solo."
    fun setInkMatches(matches: Boolean) = "¿Coincide ahora? " + if (matches) "sí: modo Ink Paper" else "no"
    const val SET_READING = "Lectura"
    const val SET_FIT = "Ajuste por defecto"
    const val SET_FIT_WIDTH = "Ancho"
    const val SET_FIT_HEIGHT = "Alto"
    const val SET_FIT_BOTH = "Ambos"
    const val SET_LAYOUT = "Páginas por defecto"
    const val SET_LAYOUT_SINGLE = "Una página"
    const val SET_LAYOUT_DOUBLE = "Doble en horizontal"
    const val SET_LAYOUT_VERTICAL = "Vertical continuo"
    const val SET_RTL_GLOBAL = "Manga (derecha a izquierda) para todo"
    const val SET_RTL_GLOBAL_HELP = "Cada carpeta o cómic puede cambiarlo con una pulsación larga."
    const val SET_VOLUME_KEYS = "Pasar página con las teclas de volumen"
    const val SET_KEEP_SCREEN = "Mantener la pantalla encendida al leer"
    const val SET_TAP_ZONES = "Pasar página tocando los lados"
    const val SET_CACHE = "Descargas y caché"
    const val SET_CACHE_LIMIT = "Tope de la caché"
    const val SET_MIN_FREE = "Dejar siempre libre en la tablet"
    const val SET_WIFI_ONLY = "Descargar el siguiente número solo con Wi-Fi"
    const val SET_PREFETCH = "Descargar el siguiente número mientras lees"
    fun setCacheState(used: String, limit: String, free: String) = "Usados $used de $limit · libres en la tablet $free"
    fun setCacheEntries(n: Int, pinned: Int) = "${UiFormat.comics(n)} en la tablet, ${UiFormat.count(pinned)} fijados"
    const val SET_CLEAR_CACHE = "Vaciar caché"
    const val SET_CLEAR_CACHE_TITLE = "¿Vaciar la caché?"
    const val SET_CLEAR_CACHE_BODY =
        "Se borran de la tablet todos los cómics descargados, también los fijados. El progreso de lectura no se toca."
    const val SET_CLEAR_CACHE_DONE = "Caché vaciada"
    const val SET_LIBRARY = "Biblioteca"
    fun setRoot(name: String?) = "Carpeta raíz: " + (name ?: "sin elegir")
    const val SET_CHANGE_ROOT = "Cambiar"
    const val SET_RESCAN = "Volver a escanear"
    const val SET_PROGRESS = "Progreso de lectura"
    const val SET_BACKUP = "Copiar progreso a Drive"
    const val SET_RESTORE = "Restaurar de Drive"
    fun setLastBackup(date: String?) = "Última copia: " + (date ?: "ninguna")
    const val SET_BACKUP_DONE = "Progreso copiado en Drive"
    fun setRestoreDone(n: Int) = "Restaurado el progreso de ${UiFormat.comics(n)}"
    const val SET_RESTORE_TITLE = "¿Restaurar el progreso de Drive?"
    const val SET_RESTORE_BODY = "Se trae la copia guardada en Drive y se combina con el progreso de esta tablet."
    const val ERR_BACKUP = "No se pudo copiar el progreso"
    const val ERR_RESTORE = "No se pudo restaurar el progreso"
    const val ERR_CLEAR_CACHE = "No se pudo vaciar la caché"
    const val SET_ADVANCED = "Avanzado"
    const val SET_DIAGNOSTICO = "Diagnóstico"
    const val SET_DIAGNOSTICO_HELP = "La sonda de la tablet: conexión, descarga, archivos y claves NXTPAPER."
    fun setVersion(v: String) = "Versión $v"
    const val GB_SUFFIX = " GB"

    // Primer arranque
    const val ONB_TITLE = "Conecta tu Google Drive"
    const val ONB_BODY =
        "Cómics lee tu carpeta de cómics de Google Drive. Solo pide permiso de lectura, y una carpeta oculta de la app para copiar tu progreso."
    const val ONB_CONNECT = "Conectar Google Drive"
    const val ONB_CHECKING = "Comprobando la conexión con Google…"
    const val ONB_WAITING = "Esperando el permiso de Google…"
    const val ONB_LOOKING = "Buscando tu carpeta «Cómics» en Drive…"
    const val ONB_FOUND_TITLE = "Encontré tu carpeta"
    fun onbFoundBody(name: String) = "En Mi unidad hay una carpeta llamada «$name». ¿Es la de tus cómics?"
    const val ONB_USE_THIS = "Usar esta carpeta"
    const val ONB_CHOOSE_OTHER = "Elegir otra"
    const val ONB_PICK_TITLE = "Elige la carpeta de tus cómics"
    const val ONB_PICK_HELP =
        "Solo se ven carpetas. Entra en la que guarda tus cómics (con Americano, Europeo, Manga…) y pulsa «Usar»."
    fun onbUse(name: String) = "Usar «$name»"
    const val ONB_NO_SUBFOLDERS = "Esta carpeta no tiene subcarpetas."
    const val ONB_SAVING = "Guardando y preparando la biblioteca…"
    const val ONB_CONSENT_DENIED = "No se concedió el permiso. Sin él no puedo leer tus cómics."
    fun onbError(message: String?) = "No se pudo completar: " + (message ?: "error desconocido")
    const val ONB_UP = "Subir un nivel"
    const val ONB_CANCEL_CHANGE = "Dejar la carpeta como estaba"

    // Lector (envoltorio)
    const val READER_BOOK_GONE = "Este cómic ya no está en la biblioteca."
}
