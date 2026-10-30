package mx.jano.comics.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * Iconos que material-icons-core no trae (nube, nube tachada, chincheta, tablet, filtro, medio círculo).
 * Trazos propios sobre una rejilla de 24 × 24; el color lo pone el tinte de Icon.
 */
object ComicIcons {
    private fun icon(name: String, build: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply(build).build()

    private fun ImageVector.Builder.fill(d: String) {
        addPath(PathParser().parsePathString(d).toNodes(), fill = SolidColor(Color.Black))
    }

    private fun ImageVector.Builder.stroke(d: String, width: Float = 2f) {
        addPath(
            PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }

    private const val CLOUD_OUTLINE =
        "M7,19 H18 A4,4 0 0,0 18.6,11.05 A6,6 0 0,0 6.9,9.6 A4.7,4.7 0 0,0 7,19 Z"

    /** En la nube: no descargado. */
    val Cloud: ImageVector by lazy { icon("Nube") { stroke(CLOUD_OUTLINE) } }

    /** Sin conexión / no disponible sin conexión. */
    val CloudOff: ImageVector by lazy {
        icon("NubeTachada") {
            stroke(CLOUD_OUTLINE)
            stroke("M3,3 L21,21", width = 2.2f)
        }
    }

    /** Fijado para leer sin conexión. */
    val Pin: ImageVector by lazy {
        icon("Chincheta") {
            fill("M16,9 V4 H17 A1,1 0 0,0 17,2 H7 A1,1 0 0,0 7,4 H8 V9 A3,3 0 0,1 5,12 V14 H11 V21 L12,22 L13,21 V14 H19 V12 A3,3 0 0,1 16,9 Z")
        }
    }

    /** Descargado en la tablet. */
    val Tablet: ImageVector by lazy {
        icon("Tablet") {
            stroke("M7,2.5 H17 A2,2 0 0,1 19,4.5 V19.5 A2,2 0 0,1 17,21.5 H7 A2,2 0 0,1 5,19.5 V4.5 A2,2 0 0,1 7,2.5 Z")
            stroke("M10.5,18.5 H13.5", width = 1.6f)
        }
    }

    val Filter: ImageVector by lazy {
        icon("Filtro") {
            stroke("M4,7 H20")
            stroke("M7,12 H17")
            stroke("M10,17 H14")
        }
    }

    /** Marca «en curso»: círculo con la mitad izquierda rellena. */
    val HalfCircle: ImageVector by lazy {
        icon("MedioCirculo") {
            stroke("M12,3 A9,9 0 1,1 12,21 A9,9 0 1,1 12,3 Z")
            fill("M12,3 A9,9 0 0,0 12,21 Z")
        }
    }

    /** Pilas de portadas (para «Carpeta»). */
    val Stack: ImageVector by lazy {
        icon("Pila") {
            stroke("M8,3.5 H19 A1.5,1.5 0 0,1 20.5,5 V16")
            stroke("M5.5,7 H15.5 A1.5,1.5 0 0,1 17,8.5 V19 A1.5,1.5 0 0,1 15.5,20.5 H5.5 A1.5,1.5 0 0,1 4,19 V8.5 A1.5,1.5 0 0,1 5.5,7 Z")
        }
    }
}
