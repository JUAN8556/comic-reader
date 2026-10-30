package mx.jano.comics.ui.theme

import mx.jano.comics.settings.ThemeMode

/**
 * Claves de ajustes de TCL que cambian con el modo NXTPAPER (la sonda las lee sin SecurityException). El valor de cada
 * modo aún no está medido: el usuario marca «Este es el modo Ink Paper» y se guardan los valores de ese momento.
 */
object NxtPaperKeys {
    val WATCHED: List<Pair<String, String>> = listOf(
        "secure" to "nxtpaper_ink_style_state",
        "system" to "color_mode_value",
        "system" to "adv_color_mode_value",
    )

    fun id(table: String, key: String): String = "$table/$key"

    /** «secure/nxtpaper_ink_style_state=2 · system/color_mode_value=1 · …» */
    fun describe(values: Map<String, String?>): String =
        WATCHED.joinToString(" · ") { (t, k) -> "${id(t, k)}=${values[id(t, k)] ?: "null"}" }
}

object ThemeResolver {
    /**
     * ¿Es el modo Ink Paper? Sí cuando hay valores guardados (alguno no nulo) y TODOS coinciden con los actuales.
     * Estricto a propósito: si otra opción de TCL mueve una de las claves, Ajustes enseña «¿Coincide ahora? no».
     */
    fun isInkPaper(current: Map<String, String?>, saved: Map<String, String?>?): Boolean {
        if (saved.isNullOrEmpty() || saved.values.all { it == null }) return false
        return saved.all { (k, v) -> current[k] == v }
    }

    fun useHighContrast(mode: ThemeMode, current: Map<String, String?>, saved: Map<String, String?>?): Boolean =
        when (mode) {
            ThemeMode.COVER_DARK -> false
            ThemeMode.HIGH_CONTRAST -> true
            ThemeMode.AUTO_NXTPAPER -> isInkPaper(current, saved)
        }
}
