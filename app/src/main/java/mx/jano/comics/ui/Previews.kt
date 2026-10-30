package mx.jano.comics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.tooling.preview.Preview
import mx.jano.comics.ui.fakes.FakeAppGraph
import mx.jano.comics.ui.fakes.FakeData
import mx.jano.comics.ui.fakes.PreviewEnv
import mx.jano.comics.ui.library.LibraryScreen
import mx.jano.comics.ui.nav.Navigator
import mx.jano.comics.ui.now.NowScreen
import mx.jano.comics.ui.search.SearchScreen
import mx.jano.comics.ui.series.SeriesScreen
import mx.jano.comics.ui.settings.SettingsScreen

// Vistas previas al tamaño de la NXTPAPER 11 Plus: 720 × 1100 dp en vertical, 1100 × 720 en horizontal.

@Preview(name = "Biblioteca · raíz · vertical", widthDp = 720, heightDp = 1100)
@Composable
private fun LibraryRootPreview() = PreviewEnv { LibraryScreen(FakeData.ROOT, Navigator.None) }

@Preview(name = "Biblioteca · serie · horizontal", widthDp = 1100, heightDp = 720)
@Composable
private fun LibrarySeriesPreview() = PreviewEnv { LibraryScreen("xmen", Navigator.None) }

@Preview(name = "Biblioteca · alto contraste", widthDp = 720, heightDp = 1100)
@Composable
private fun LibraryHighContrastPreview() = PreviewEnv(highContrast = true) { LibraryScreen("xmen", Navigator.None) }

@Preview(name = "Biblioteca · sin conexión", widthDp = 720, heightDp = 1100)
@Composable
private fun LibraryOfflinePreview() {
    val graph = remember { FakeAppGraph(online = false, local = setOf("xmen-b2"), pinned = setOf("xmen-b3")) }
    PreviewEnv(graph) { LibraryScreen("xmen", Navigator.None) }
}

@Preview(name = "Leyendo ahora", widthDp = 720, heightDp = 1100)
@Composable
private fun NowPreview() = PreviewEnv { NowScreen(Navigator.None) }

@Preview(name = "Búsqueda", widthDp = 720, heightDp = 1100)
@Composable
private fun SearchPreview() = PreviewEnv { SearchScreen(Navigator.None) }

@Preview(name = "Ficha de serie", widthDp = 720, heightDp = 1100)
@Composable
private fun SeriesPreview() = PreviewEnv { SeriesScreen("berserk", onBack = {}) }

@Preview(name = "Ajustes", widthDp = 720, heightDp = 1600)
@Composable
private fun SettingsPreview() = PreviewEnv { SettingsScreen(Navigator.None) }
