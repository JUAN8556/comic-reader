package mx.jano.comics.reader

/** Rectángulo entero en píxeles, en coordenadas del lector (sin depender de Compose para las pruebas). */
data class PxRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Arreglo de UI-4 (sonda del 30-oct): con la navegación por gestos (navigation_mode=2) un deslizamiento que empieza
 * en el borde es «atrás» y cerraba el lector.
 *
 * Tres capas, de más a menos fuerte:
 * 1. **Toques laterales como vía principal** para pasar página (tercios izquierdo y derecho; teclas de volumen).
 * 2. **Exclusión del gesto del sistema** en la franja CENTRAL de cada borde: Android solo respeta [MAX_BAND_DP] dp de
 *    alto por borde (lo que se pida de más lo ignora), así que se excluye una franja centrada de ese alto y del ancho
 *    de la zona de gesto del sistema. Por encima y por debajo de la franja el borde sigue siendo «atrás».
 * 3. **«Atrás» no cierra a la primera**: sin la superposición, «atrás» la abre y avisa; con ella abierta, cierra.
 *    Un deslizamiento accidental desde el borde ya no saca al lector del libro.
 */
object EdgeGuard {
    /** Límite de Android (View.setSystemGestureExclusionRects): 200 dp de alto por borde. */
    const val MAX_BAND_DP = 200
    /** Ancho de la franja si el sistema no informa de la zona de gesto (navegación por botones o insets aún a 0). */
    const val FALLBACK_EDGE_DP = 32

    /**
     * Rectángulos a excluir en un lector de [width]×[height] px. [leftInset]/[rightInset] = ancho de la zona de gesto
     * de cada lado (WindowInsets.systemGestures); si es 0 se usa [fallbackEdgePx]. [maxBandPx] = 200 dp en px.
     */
    fun exclusionRects(
        width: Int,
        height: Int,
        leftInset: Int,
        rightInset: Int,
        maxBandPx: Int,
        fallbackEdgePx: Int,
    ): List<PxRect> {
        if (width <= 0 || height <= 0 || maxBandPx <= 0) return emptyList()
        val band = minOf(maxBandPx, height)
        val top = (height - band) / 2
        val bottom = top + band
        val l = (if (leftInset > 0) leftInset else fallbackEdgePx).coerceIn(0, width / 2)
        val r = (if (rightInset > 0) rightInset else fallbackEdgePx).coerceIn(0, width / 2)
        val out = ArrayList<PxRect>(2)
        if (l > 0) out += PxRect(0, top, l, bottom)
        if (r > 0) out += PxRect(width - r, top, width, bottom)
        return out
    }
}
