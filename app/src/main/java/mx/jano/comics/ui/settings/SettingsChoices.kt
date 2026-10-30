package mx.jano.comics.ui.settings

/** Opciones fijas de Ajustes (decisión de Jano: tope 20 GB y 15 GB libres por defecto). */
object SettingsChoices {
    val CACHE_LIMITS_GB = listOf(10, 20, 40, 100)
    val MIN_FREE_GB = listOf(5, 10, 15, 20)

    /** Si el valor guardado no es una de las opciones, se añade para que se vea elegido. */
    fun withCurrent(options: List<Int>, current: Int): List<Int> = if (current in options) options else (options + current).sorted()
}
