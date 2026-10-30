package mx.jano.comics.reader

import mx.jano.comics.settings.PageLayout

/** Tamaño en píxeles de una página tal y como está en el archivo (leído con inJustDecodeBounds al extraerla). */
data class PageSize(val width: Int, val height: Int) {
    val aspect: Float get() = if (height <= 0) 0f else width.toFloat() / height.toFloat()
    /** Apaisada: más ancha que alta (una doble página escaneada como una sola imagen). */
    val isLandscape: Boolean get() = width > height
}

/** Cómo se ve el libro ahora, después de mirar la orientación de la pantalla. */
enum class EffectiveLayout { SINGLE, DOUBLE, VERTICAL }

/**
 * Un pliego: una o dos páginas que se ven a la vez. [first] < [second]: siempre en orden de lectura ascendente,
 * el sentido de la pantalla lo pone [leftToRight].
 */
data class Spread(val first: Int, val second: Int? = null) {
    init {
        require(first >= 0) { "página negativa: $first" }
        require(second == null || second == first + 1) { "un pliego doble son dos páginas seguidas: $first y $second" }
    }

    val last: Int get() = second ?: first
    val isDouble: Boolean get() = second != null
    val pages: List<Int> get() = if (second == null) listOf(first) else listOf(first, second)

    /** Páginas de izquierda a derecha en la pantalla: en manga la menor va a la derecha. */
    fun leftToRight(rtl: Boolean): List<Int> = if (rtl) pages.asReversed() else pages

    operator fun contains(page: Int): Boolean = page == first || page == second
}

/**
 * Mapa página ↔ pliego de un libro. El slider y el progreso siguen en páginas; el pager trabaja en pliegos.
 * Inmutable: cuando se aprende la proporción de una página se construye otro.
 */
class SpreadMap internal constructor(
    val pageCount: Int,
    val layout: EffectiveLayout,
    val spreads: List<Spread>,
) {
    private val pageToSpread = IntArray(pageCount).also { table ->
        spreads.forEachIndexed { i, s -> s.pages.forEach { p -> table[p] = i } }
    }

    val size: Int get() = spreads.size
    val isEmpty: Boolean get() = spreads.isEmpty()

    operator fun get(index: Int): Spread = spreads[index]

    /** Pliego que contiene [page] (fuera de rango se acota a la primera o la última página). */
    fun spreadOf(page: Int): Int = if (pageCount == 0) 0 else pageToSpread[page.coerceIn(0, pageCount - 1)]

    fun firstPageOf(spread: Int): Int = if (spreads.isEmpty()) 0 else spreads[spread.coerceIn(0, spreads.size - 1)].first
    fun lastPageOf(spread: Int): Int = if (spreads.isEmpty()) 0 else spreads[spread.coerceIn(0, spreads.size - 1)].last

    /** ¿Este pliego enseña la última página del libro? */
    fun isLastSpread(spread: Int): Boolean = spreads.isNotEmpty() && spread == spreads.size - 1

    /** Las páginas de los pliegos [fromSpread, toSpread] (acotados), en orden ascendente. */
    fun pagesOfSpreads(fromSpread: Int, toSpread: Int): List<Int> {
        if (spreads.isEmpty()) return emptyList()
        val a = fromSpread.coerceIn(0, spreads.size - 1)
        val b = toSpread.coerceIn(0, spreads.size - 1)
        if (a > b) return emptyList()
        return (a..b).flatMap { spreads[it].pages }
    }

    /** Mismo emparejamiento (lo que importa para no mover el pager). */
    fun samePairingAs(other: SpreadMap): Boolean = pageCount == other.pageCount && spreads == other.spreads

    override fun toString(): String = "SpreadMap($layout, $pageCount págs, ${spreads.joinToString(" ") { s -> s.pages.joinToString("-") }})"
}

/**
 * Motor de pliegos (lógica pura, sin Android).
 *
 * - SINGLE y VERTICAL: un pliego por página.
 * - DOUBLE (= DOUBLE_AUTO con la pantalla en horizontal): la portada (índice 0) va sola; toda página apaisada
 *   (ancho > alto) va sola; el resto se empareja de dos en dos y, tras una apaisada, se vuelve a emparejar a partir
 *   de la siguiente. Una página vertical sin pareja (la última, o la anterior a una apaisada) va sola.
 * - Una página cuya proporción aún no se conoce cuenta como vertical: el emparejamiento se rehace cuando se aprende.
 */
object SpreadEngine {

    fun effectiveLayout(layout: PageLayout, landscape: Boolean): EffectiveLayout = when (layout) {
        PageLayout.SINGLE -> EffectiveLayout.SINGLE
        PageLayout.DOUBLE_AUTO -> if (landscape) EffectiveLayout.DOUBLE else EffectiveLayout.SINGLE
        PageLayout.VERTICAL -> EffectiveLayout.VERTICAL
    }

    fun build(pageCount: Int, layout: EffectiveLayout, sizeOf: (Int) -> PageSize?): SpreadMap {
        val count = pageCount.coerceAtLeast(0)
        if (layout != EffectiveLayout.DOUBLE) {
            return SpreadMap(count, layout, List(count) { Spread(it) })
        }
        val out = ArrayList<Spread>(count / 2 + 2)
        fun landscape(i: Int): Boolean = sizeOf(i)?.isLandscape == true
        if (count > 0) out += Spread(0)
        var i = 1
        while (i < count) {
            when {
                landscape(i) -> { out += Spread(i); i += 1 }
                i + 1 < count && !landscape(i + 1) -> { out += Spread(i, i + 1); i += 2 }
                else -> { out += Spread(i); i += 1 }
            }
        }
        return SpreadMap(count, layout, out)
    }

    /** Atajo con un mapa de tamaños conocidos. */
    fun build(pageCount: Int, layout: EffectiveLayout, sizes: Map<Int, PageSize>): SpreadMap =
        build(pageCount, layout) { sizes[it] }
}
