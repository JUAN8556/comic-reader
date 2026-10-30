package mx.jano.comics.reader

import mx.jano.comics.settings.FitMode
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Escala base del contenido (lo que Telephoto recibe como contentScale). */
enum class ScaleKind { FIT, FILL_WIDTH, FILL_HEIGHT }

/** Dónde arranca el contenido que desborda (alineación absoluta: izquierda es izquierda también en manga). */
enum class AlignKind { CENTER, TOP_CENTER, CENTER_LEFT, CENTER_RIGHT }

data class FitSpec(val scale: ScaleKind, val align: AlignKind)

/** Tamaño entero en píxeles (sin depender de Compose, para las pruebas JVM). */
data class PxSize(val width: Int, val height: Int)

/**
 * Resolución de los ajustes de encaje (lógica pura).
 *
 * - BOTH: la página entera cabe en pantalla (Fit), centrada.
 * - WIDTH: ocupa el ancho; si desborda en vertical se desplaza y empieza ARRIBA.
 * - HEIGHT: ocupa el alto; si desborda en horizontal empieza por el lado donde se lee primero
 *   (izquierda en occidental, derecha en manga).
 */
object FitResolver {

    fun resolve(fit: FitMode, rtl: Boolean): FitSpec = when (fit) {
        FitMode.BOTH -> FitSpec(ScaleKind.FIT, AlignKind.CENTER)
        FitMode.WIDTH -> FitSpec(ScaleKind.FILL_WIDTH, AlignKind.TOP_CENTER)
        FitMode.HEIGHT -> FitSpec(ScaleKind.FILL_HEIGHT, if (rtl) AlignKind.CENTER_RIGHT else AlignKind.CENTER_LEFT)
    }

    /**
     * Igual que [resolve] pero mirando si el contenido desborda de verdad: Telephoto usa la alineación también cuando
     * el contenido es MÁS PEQUEÑO que el visor, y una página que no desborda debe quedar centrada (a lo ancho, una
     * panorámica más baja que la pantalla; al alto, una vertical más estrecha). Sin tamaño, se supone que desborda.
     */
    fun resolve(fit: FitMode, rtl: Boolean, content: PxSize?, viewport: PxSize?): FitSpec {
        val base = resolve(fit, rtl)
        if (content == null || viewport == null || content.width <= 0 || content.height <= 0 ||
            viewport.width <= 0 || viewport.height <= 0
        ) return base
        val shown = displayedSize(base.scale, content, viewport)
        val overflows = when (base.scale) {
            ScaleKind.FIT -> false
            ScaleKind.FILL_WIDTH -> shown.height > viewport.height + 1
            ScaleKind.FILL_HEIGHT -> shown.width > viewport.width + 1
        }
        return if (overflows) base else base.copy(align = AlignKind.CENTER)
    }

    /** En el modo vertical continuo las páginas siempre van a lo ancho: el ajuste no aplica. */
    fun fitApplies(layout: EffectiveLayout): Boolean = layout != EffectiveLayout.VERTICAL

    /** Factor por el que se multiplica un contenido [content] para dibujarlo en [viewport] con [scale]. */
    fun baseScale(scale: ScaleKind, content: PxSize, viewport: PxSize): Float {
        if (content.width <= 0 || content.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1f
        val sx = viewport.width.toFloat() / content.width
        val sy = viewport.height.toFloat() / content.height
        return when (scale) {
            ScaleKind.FIT -> min(sx, sy)
            ScaleKind.FILL_WIDTH -> sx
            ScaleKind.FILL_HEIGHT -> sy
        }
    }

    /** Tamaño en pantalla del contenido con la escala base (puede desbordar el visor en WIDTH y HEIGHT). */
    fun displayedSize(scale: ScaleKind, content: PxSize, viewport: PxSize): PxSize {
        val s = baseScale(scale, content, viewport)
        return PxSize((content.width * s).roundToInt().coerceAtLeast(1), (content.height * s).roundToInt().coerceAtLeast(1))
    }

    /**
     * Tamaño al que decodificar una imagen que se dibujará a [displayed] píxeles: el de pantalla, nunca mayor que el
     * original, y con un tope de [maxPixels] (una tira larga a lo ancho no puede pedir un mapa de bits de 200 MB).
     */
    fun decodeSize(displayed: PxSize, original: PxSize?, maxPixels: Long): PxSize {
        var w = displayed.width.toDouble().coerceAtLeast(1.0)
        var h = displayed.height.toDouble().coerceAtLeast(1.0)
        if (original != null && original.width > 0 && original.height > 0) {
            val down = min(1.0, min(original.width / w, original.height / h))
            w *= down
            h *= down
        }
        val pixels = w * h
        if (maxPixels > 0 && pixels > maxPixels) {
            val k = sqrt(maxPixels / pixels)
            w *= k
            h *= k
        }
        return PxSize(max(1, w.roundToInt()), max(1, h.roundToInt()))
    }

    /**
     * Pliego doble: las dos páginas a la misma altura (la mayor de las dos), una junto a otra.
     * Devuelve el tamaño del conjunto y el ancho de cada mitad en esa escala.
     */
    fun spreadContent(left: PxSize, right: PxSize): SpreadGeometry {
        val h = max(left.height, right.height).coerceAtLeast(1)
        val wl = if (left.height > 0) left.width.toDouble() * h / left.height else 0.0
        val wr = if (right.height > 0) right.width.toDouble() * h / right.height else 0.0
        return SpreadGeometry(leftWidth = wl.toFloat(), rightWidth = wr.toFloat(), height = h.toFloat())
    }

    /** Tope de píxeles por imagen decodificada: dos pantallas (para una mitad de pliego, una). */
    fun pixelBudget(viewport: PxSize, halves: Int): Long {
        val screen = viewport.width.toLong() * viewport.height.toLong()
        return if (halves >= 2) screen else screen * 2
    }
}

/** Geometría de un pliego doble en las unidades de la página más alta. */
data class SpreadGeometry(val leftWidth: Float, val rightWidth: Float, val height: Float) {
    val width: Float get() = leftWidth + rightWidth
}
