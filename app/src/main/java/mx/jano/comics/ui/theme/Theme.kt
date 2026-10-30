package mx.jano.comics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Paleta de Cover: fondo oscuro, acento rojo.
// La variante de alto contraste para Ink Paper llega con el lector (decisión 12 de Jano).
private val CoverDark = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    background = Color(0xFF121212),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1C1C1C),
    onSurface = Color(0xFFEDEDED),
)

@Composable
fun ComicsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CoverDark, content = content)
}
