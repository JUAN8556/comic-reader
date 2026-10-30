package mx.jano.comics.reader

/**
 * Qué página se extrae a continuación (lógica pura). Orden:
 * 1. Las urgentes (alguien en pantalla las espera), la más cercana al foco primero.
 * 2. Las de la ventana (pliegos de alrededor), por cercanía al foco y con preferencia por las de DELANTE
 *    en el sentido de lectura: a igual distancia, la siguiente antes que la anterior.
 * 3. El barrido (solo en doble página): la primera página, desde el principio, de la que no se sabe el tamaño;
 *    sirve para que el emparejamiento no cambie bajo los ojos del lector.
 * Las que fallaron no se reintentan solas: solo si vuelven a pedirse como urgentes (botón «Reintentar»).
 */
internal object ExtractionPlanner {

    /** Coste de extraer [page] con el lector en [focus]: delante cuesta 2 por página, detrás 2 + 1. */
    fun cost(page: Int, focus: Int): Int =
        if (page >= focus) (page - focus) * 2 else (focus - page) * 2 + 1

    fun pick(
        pageCount: Int,
        focus: Int,
        urgent: Collection<Int>,
        window: Collection<Int>,
        isDone: (Int) -> Boolean,
        isFailed: (Int) -> Boolean,
        sweep: Boolean,
    ): Int? {
        urgent.asSequence()
            .filter { it in 0 until pageCount && !isDone(it) }
            .minWithOrNull(compareBy<Int> { cost(it, focus) }.thenBy { it })
            ?.let { return it }
        window.asSequence()
            .filter { it in 0 until pageCount && !isDone(it) && !isFailed(it) }
            .minWithOrNull(compareBy<Int> { cost(it, focus) }.thenBy { it })
            ?.let { return it }
        if (sweep) {
            for (i in 0 until pageCount) if (!isDone(i) && !isFailed(i)) return i
        }
        return null
    }

    /** Ventana de extracción en modo paginado: el pliego anterior y [ahead] por delante del actual. */
    fun pagedWindow(map: SpreadMap, currentSpread: Int, ahead: Int = 2, behind: Int = 1): List<Int> =
        map.pagesOfSpreads(currentSpread - behind, currentSpread + ahead)

    /** Ventana en vertical continuo: ±[radius] páginas alrededor de las visibles. */
    fun verticalWindow(firstVisible: Int, lastVisible: Int, pageCount: Int, radius: Int = 3): List<Int> {
        if (pageCount <= 0) return emptyList()
        val a = (firstVisible - radius).coerceIn(0, pageCount - 1)
        val b = (maxOf(firstVisible, lastVisible) + radius).coerceIn(0, pageCount - 1)
        return (a..b).toList()
    }
}
