package mx.jano.comics.reader

/** Lo que el lector elige para ESTE libro en el botón «Manga». */
enum class MangaChoice { YES, NO, INHERIT }

/**
 * Resolución del modo manga de un libro (lógica pura). El contrato de ajustes da el RTL efectivo
 * (`rtlFor`: libro > carpeta más cercana > global) y permite fijarlo (`setRtl(ruta, Boolean?)`), pero no dice si el
 * libro tiene valor propio. Para saber qué heredaría se pregunta por una ruta HERMANA que no puede ser un libro real
 * ([inheritedProbePath]): da la carpeta o el global. Si el efectivo coincide con lo heredado se muestra «Heredar»
 * (no se distingue de un valor propio igual al heredado, y en pantalla es lo mismo).
 */
object MangaResolution {
    private const val PROBE_NAME = "\u0000heredado"

    fun inheritedProbePath(bookPath: String): String {
        val folder = bookPath.substringBeforeLast('/', "")
        return if (folder.isEmpty()) PROBE_NAME else "$folder/$PROBE_NAME"
    }

    fun choiceFor(effective: Boolean, inherited: Boolean): MangaChoice = when {
        effective == inherited -> MangaChoice.INHERIT
        effective -> MangaChoice.YES
        else -> MangaChoice.NO
    }

    /** Lo que se pasa a `setRtl(book.path, …)`: null = heredar. */
    fun override(choice: MangaChoice): Boolean? = when (choice) {
        MangaChoice.YES -> true
        MangaChoice.NO -> false
        MangaChoice.INHERIT -> null
    }

    fun effective(choice: MangaChoice, inherited: Boolean): Boolean = override(choice) ?: inherited
}
