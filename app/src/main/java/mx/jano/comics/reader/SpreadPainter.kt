package mx.jano.comics.reader

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.min

/**
 * Dibuja dos páginas una junto a otra a la misma altura, como un solo cuadro: así Telephoto hace zoom y desplazamiento
 * del pliego entero con `Modifier.zoomable`. [left] y [right] ya vienen en orden de PANTALLA (en manga, la página
 * menor es [right]). Cada mitad la decodifica Coil a su tamaño en pantalla (la mitad del pliego).
 *
 * El tamaño intrínseco sale de [geometry] (tamaños leídos de la cabecera al extraer), no de los pintores: así el cuadro
 * no cambia de forma mientras cargan. Si la imagen ya cargada tiene otra proporción (cabecera ilegible y se supuso
 * una), se encaja en su mitad sin deformarla, pegada al lomo.
 */
internal class SpreadPainter(
    private val left: Painter,
    private val right: Painter,
    private val geometry: SpreadGeometry,
) : Painter() {
    override val intrinsicSize: Size = Size(geometry.width, geometry.height)

    override fun DrawScope.onDraw() {
        if (geometry.height <= 0f || size.height <= 0f) return
        val k = size.height / geometry.height
        val h = geometry.height * k
        val wl = geometry.leftWidth * k
        val wr = geometry.rightWidth * k
        drawFitted(left, x = 0f, w = wl, h = h, towardsRight = true)
        drawFitted(right, x = wl, w = wr, h = h, towardsRight = false)
    }

    private fun DrawScope.drawFitted(painter: Painter, x: Float, w: Float, h: Float, towardsRight: Boolean) {
        if (w <= 0f || h <= 0f) return
        val own = painter.intrinsicSize
        if (own.isUnspecified || own.width <= 0f || own.height <= 0f) {
            translate(left = x) { with(painter) { draw(Size(w, h)) } }
            return
        }
        val s = min(w / own.width, h / own.height)
        val dw = own.width * s
        val dh = own.height * s
        val dx = x + if (towardsRight) w - dw else 0f
        val dy = (h - dh) / 2f
        translate(left = dx, top = dy) { with(painter) { draw(Size(dw, dh)) } }
    }
}
