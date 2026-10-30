package mx.jano.comics.reader

import java.io.File
import java.util.Locale

/** Qué hace un toque simple sobre la página según dónde cae. */
enum class TapAction { PREVIOUS, NEXT, TOGGLE_OVERLAY }

/**
 * Zonas de toque del lector: tercio izquierdo, central y derecho del ancho de la página.
 * En manga (RTL) los laterales se invierten: el tercio izquierdo avanza, como al pasar la hoja de un tomo japonés.
 * Lógica pura (sin Android) para probarla en la JVM.
 */
object TapZones {
    fun actionFor(x: Float, width: Float, rtl: Boolean): TapAction {
        if (width <= 0f || x.isNaN()) return TapAction.TOGGLE_OVERLAY
        val third = width / 3f
        return when {
            x < third -> if (rtl) TapAction.NEXT else TapAction.PREVIOUS
            x > width - third -> if (rtl) TapAction.PREVIOUS else TapAction.NEXT
            else -> TapAction.TOGGLE_OVERLAY
        }
    }

    /** Página destino de una acción lateral, sin salirse de [0, pageCount). Null si no hay a dónde ir. */
    fun targetPage(action: TapAction, current: Int, pageCount: Int): Int? {
        val target = when (action) {
            TapAction.PREVIOUS -> current - 1
            TapAction.NEXT -> current + 1
            TapAction.TOGGLE_OVERLAY -> return null
        }
        return target.takeIf { it in 0 until pageCount }
    }
}

/** Nombres de las páginas extraídas en la caché del lector: `<índice>.<ext>`. */
object PageFiles {
    private val SAFE_EXT = Regex("^[a-z0-9]{1,5}$")

    /**
     * Extensión del fichero de caché a partir del nombre de la página dentro del archivo.
     * Solo letras y dígitos (hasta 5); si no hay o es rara, «img» (Coil decodifica por contenido, no por extensión).
     */
    fun extensionFor(pageName: String): String {
        val base = pageName.substringAfterLast('/').substringAfterLast('\\')
        if (!base.contains('.')) return "img"
        val ext = base.substringAfterLast('.').lowercase(Locale.ROOT)
        return if (SAFE_EXT.matches(ext)) ext else "img"
    }

    fun fileFor(dir: File, index: Int, pageName: String): File = File(dir, "$index.${extensionFor(pageName)}")
}
