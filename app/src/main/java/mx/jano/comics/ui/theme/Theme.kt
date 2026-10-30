package mx.jano.comics.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Colores de Cover: fondo #121212, acento rojo #E53935. */
object CoverColors {
    val Background = Color(0xFF121212)
    val Surface = Color(0xFF1C1C1C)
    val SurfaceHigh = Color(0xFF262626)
    val Accent = Color(0xFFE53935)
    val OnDark = Color(0xFFEDEDED)
    val OnDarkMuted = Color(0xFFB3B3B3)
    val Outline = Color(0xFF4A4A4A)
    /** Velo sobre la portada difuminada del fondo. */
    val Scrim = Color(0xD9121212)
}

private val CoverDark = darkColorScheme(
    primary = CoverColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF5C1513),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color.Black,
    secondaryContainer = CoverColors.SurfaceHigh,
    onSecondaryContainer = CoverColors.OnDark,
    background = CoverColors.Background,
    onBackground = CoverColors.OnDark,
    surface = CoverColors.Surface,
    onSurface = CoverColors.OnDark,
    surfaceVariant = CoverColors.SurfaceHigh,
    onSurfaceVariant = CoverColors.OnDarkMuted,
    surfaceContainer = CoverColors.Surface,
    surfaceContainerHigh = CoverColors.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF303030),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainerLowest = Color(0xFF0E0E0E),
    outline = CoverColors.Outline,
    outlineVariant = Color(0xFF333333),
    error = Color(0xFFFF8A80),
    onError = Color.Black,
)

/**
 * Alto contraste para el modo Ink Paper (la pantalla pasa a grises): blanco sobre negro puro, sin acento de color.
 * Ningún estado depende del color en ningún tema (icono o texto siempre), pero aquí además todo es blanco o negro.
 */
private val HighContrast = darkColorScheme(
    primary = Color.White,
    onPrimary = Color.Black,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    secondary = Color.White,
    onSecondary = Color.Black,
    secondaryContainer = Color.Black,
    onSecondaryContainer = Color.White,
    tertiary = Color.White,
    onTertiary = Color.Black,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color.White,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceTint = Color.Black,
    inverseSurface = Color.White,
    inverseOnSurface = Color.Black,
    outline = Color.White,
    outlineVariant = Color.White,
    error = Color.White,
    onError = Color.Black,
)

/** Lo que cambia con el tema además de los colores. */
@Immutable
data class ComicsLook(
    val highContrast: Boolean,
    /** Portada difuminada de fondo y sombras de las pilas. */
    val decorations: Boolean = !highContrast,
    /** Transiciones y animación del gesto atrás. */
    val animations: Boolean = !highContrast,
)

val LocalComicsLook = staticCompositionLocalOf { ComicsLook(highContrast = false) }

private val BaseTypography = Typography()

/** Tipografía limpia (la del sistema) con títulos algo más firmes. */
private val ComicsTypography = BaseTypography.copy(
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelSmall = BaseTypography.labelSmall.copy(fontSize = 12.sp),
)

/** Tema de la app. [highContrast] lo decide la Activity (ajuste del tema y, en automático, el modo NXTPAPER). */
@Composable
fun ComicsTheme(highContrast: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalComicsLook provides ComicsLook(highContrast)) {
        MaterialTheme(
            colorScheme = if (highContrast) HighContrast else CoverDark,
            typography = ComicsTypography,
            content = content,
        )
    }
}
