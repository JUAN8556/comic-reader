package mx.jano.comics.reader

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/** Lo que el modo activo (paginado o vertical) sabe hacer cuando se le pide desde fuera (toques, volumen, barra). */
internal interface ReaderNavigator {
    /** Avanza (o retrocede) en el sentido de lectura: primero dentro de la página si desborda, luego de pliego. */
    suspend fun step(forward: Boolean)
    /** Salta a la página [page] (0-based) sin animación. */
    suspend fun jumpToPage(page: Int)
}

/** Puente entre el núcleo del lector y el modo activo. */
@Stable
internal class ReaderController {
    var navigator: ReaderNavigator? = null
    /** Pliego que, al asentarse, debe mostrarse por el final (se llegó a él retrocediendo con toque o volumen). */
    var endAlignRequest by mutableStateOf<Int?>(null)
}

/**
 * Colores del lector. La variante de alto contraste (modo Ink Paper, en gris) no usa transparencias ni el rojo:
 * negro opaco, blanco y bordes; la elección de un menú se marca con un botón de radio y con texto, nunca solo con color.
 */
@Immutable
internal data class ReaderPalette(
    val highContrast: Boolean,
    val background: Color,
    val band: Color,
    val onBand: Color,
    val secondary: Color,
    val accent: Color,
    val onAccent: Color,
    val outline: Color,
    val scrim: Color,
) {
    companion object {
        val Cover = ReaderPalette(
            highContrast = false,
            background = Color(0xFF121212),
            band = Color(0xE6121212),
            onBand = Color(0xFFEDEDED),
            secondary = Color(0xFFBDBDBD),
            accent = Color(0xFFE53935),
            onAccent = Color.White,
            outline = Color(0xFF5A5A5A),
            scrim = Color(0xB3000000),
        )
        val HighContrast = ReaderPalette(
            highContrast = true,
            background = Color.Black,
            band = Color.Black,
            onBand = Color.White,
            secondary = Color.White,
            accent = Color.White,
            onAccent = Color.Black,
            outline = Color.White,
            scrim = Color.Black,
        )

        fun of(highContrast: Boolean) = if (highContrast) HighContrast else Cover
    }
}

/** ¿Un color sin tinte (gris)? Sirve para adivinar que el tema activo es el de alto contraste. */
internal fun Color.isAchromatic(tolerance: Float = 0.08f): Boolean =
    abs(red - green) < tolerance && abs(green - blue) < tolerance && abs(red - blue) < tolerance

/**
 * Ventana del lector: barras del sistema ocultas mientras [immersive] (vuelven con un deslizamiento, transitorias)
 * y visibles con la superposición; FLAG_KEEP_SCREEN_ON si [keepScreenOn]. Todo se restaura al salir.
 */
@Composable
internal fun ReaderWindowEffect(immersive: Boolean, keepScreenOn: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    DisposableEffect(window) {
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = previousBehavior
        }
    }
    LaunchedEffect(immersive) {
        if (immersive) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(window, keepScreenOn) {
        if (keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

/**
 * Excluye del gesto «atrás» la franja central de 200 dp de cada borde (ver [EdgeGuard]). El ancho es el de la zona
 * de gesto que informa el sistema (o 32 dp si no informa).
 */
@Composable
internal fun Modifier.edgeBackGuard(): Modifier {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val gestures = WindowInsets.systemGestures
    val left = gestures.getLeft(density, direction)
    val right = gestures.getRight(density, direction)
    val maxBand = with(density) { EdgeGuard.MAX_BAND_DP.dp.roundToPx() }
    val fallback = with(density) { EdgeGuard.FALLBACK_EDGE_DP.dp.roundToPx() }
    fun rectAt(index: Int): (androidx.compose.ui.layout.LayoutCoordinates) -> Rect = { coords ->
        val rects = EdgeGuard.exclusionRects(coords.size.width, coords.size.height, left, right, maxBand, fallback)
        rects.getOrNull(index)?.let { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
            ?: Rect.Zero
    }
    return this
        .systemGestureExclusion(rectAt(0))
        .systemGestureExclusion(rectAt(1))
}
