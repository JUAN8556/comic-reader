package mx.jano.comics.probe

/** Textos de la pantalla de la sonda (español). La i18n llega después. */
internal object SondaTexts {
    const val TITLE = "Sonda de Cómics"
    const val SUBTITLE = "Diagnóstico de la NXTPAPER: Drive, descarga, archivos y lector"
    const val BUSY_PREFIX = "Trabajando: "
    const val IDLE = "Libre"

    const val SECTION_DEVICE = "1. Dispositivo"
    const val DEVICE_HELP = "Se lee al arrancar. Con la vigilancia activa, cambia de modo con el botón NXTPAPER " +
        "(Color Paper / Ink Paper) y mira en el registro qué ajustes cambian."
    const val DEVICE_REPEAT = "Repetir lectura"
    const val DEVICE_INK_NOW = "Leer modo ahora"
    const val DEVICE_INK_HELP = "Pulsa «Leer modo ahora» una vez en cada modo (Normal, Color Paper, Ink Paper): " +
        "así el valor queda en el registro aunque el cambio no avise."
    const val WATCH_ON = "Vigilancia de ajustes: activa"
    const val WATCH_OFF = "Vigilancia de ajustes: apagada"

    const val SECTION_AUTH = "2. Conectar Drive"
    const val AUTH_CONNECT = "Conectar Drive"
    const val AUTH_SILENT = "Token silencioso"
    const val AUTH_FORCE = "Token nuevo (forzar)"
    const val AUTH_HELP = "«Token silencioso» es la prueba de los 8 días: debe salir sin pantalla."

    const val SECTION_LIST = "3. Listar Cómics"
    const val LIST_RUN = "Listar Cómics"
    const val LIST_EMPTY = "Sin muestra todavía."
    const val LIST_PICK_HELP = "Toca otro para usarlo como muestra:"
    const val LIST_HEADS = "Mirar cabeceras"
    const val LIST_HEADS_HELP = "«Mirar cabeceras» baja 1 KB de cada candidato y dice su formato real (RAR4, RAR5, sólido, ZIP…)."
    const val LIST_CHOSEN = "[elegida] "

    const val SECTION_DOWNLOAD = "4. Descargar (Range)"
    const val DOWNLOAD_RUN = "Descargar"
    const val DOWNLOAD_CUT = "Cortar"
    const val DOWNLOAD_DELETE = "Borrar del disco"
    const val DOWNLOAD_HELP = "«Cortar» a medias y volver a «Descargar» prueba la reanudación con Range."

    const val SECTION_OPEN = "5. Abrir archivo"
    const val OPEN_RUN = "Abrir archivo"
    const val OPEN_SYNTHETIC = "Sólido sintético"
    const val OPEN_SYNTHETIC_HELP = "«Sólido sintético» genera (la primera vez) y abre un RAR4 sólido de 60 páginas: " +
        "en Drive no hay ninguno. Mide el camino sólido de junrar y la caché, no la descompresión real."

    const val SECTION_READ = "6. Leer"
    const val READ_RUN = "Leer"
    const val READ_RTL = "Manga RTL"
    const val READ_RTL_ON = "sí (derecha a izquierda)"
    const val READ_RTL_OFF = "no (izquierda a derecha)"
    const val READ_NEEDS_OPEN = "Primero «Abrir archivo»."

    const val SECTION_LOG = "7. Registro"
    const val LOG_COPY = "Copiar registro"
    const val LOG_PATH = "Fichero: "
    const val LOG_PULL = "Desde la Mac: adb pull "
    const val LOG_NO_FILE = "Sin fichero (getExternalFilesDir devolvió null)"
    const val LOG_TITLE = "Registro en vivo (lo más reciente abajo)"
}
