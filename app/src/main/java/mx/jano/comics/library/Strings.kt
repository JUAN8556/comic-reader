package mx.jano.comics.library

/** Textos de interfaz del paquete library (español; pasan a strings.xml cuando llegue la i18n). */
internal object LibraryStrings {
    const val DEFAULT_ROOT_NAME = "Cómics"
    const val NO_ROOT = "Elige primero la carpeta de tus cómics en Google Drive."
    fun rootNotFound(name: String) = "No encuentro la carpeta «$name» en tu Google Drive. Elígela en Ajustes."
    const val NEEDS_CONSENT = "Hace falta volver a dar permiso a la app para leer Google Drive."
    const val OFFLINE = "Sin conexión: no se pudo leer Google Drive. Se sigue enseñando la última biblioteca."
    fun driveError(detail: String?) = "Google Drive no respondió como se esperaba" + (detail?.let { ": $it" } ?: ".")
    fun scanFailed(detail: String?) = "No se pudo leer la biblioteca" + (detail?.let { ": $it" } ?: ".")
    const val TOO_DEEP = "La carpeta tiene demasiados niveles de subcarpetas; se ha leído hasta el nivel 64."

    const val NO_APPDATA = "La copia en Drive no está disponible con este cliente de Drive."
    fun backupFailed(detail: String?) = "No se pudo guardar la copia del progreso en Drive" + (detail?.let { ": $it" } ?: ".")
    fun restoreFailed(detail: String?) = "No se pudo recuperar el progreso de Drive" + (detail?.let { ": $it" } ?: ".")
    const val BACKUP_CORRUPT = "La copia del progreso que hay en Drive está dañada; no se ha tocado nada."
}
