package mx.jano.comics.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import mx.jano.comics.AppGraph
import mx.jano.comics.ui.components.CoverLoader
import mx.jano.comics.ui.theme.InkModeSource

/** El grafo de la app para las pantallas. Lo pone MainActivity con el de ComicsApp; las vistas previas, uno falso. */
val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("Falta LocalAppGraph: envuelve la UI en ComicsApp o en PreviewEnv") }

/** Lo que la interfaz necesita además del grafo (contrato): preferencias propias, modo NXTPAPER, acciones, portadas. */
class UiEnv(
    val graph: AppGraph,
    val prefs: UiPrefsStore,
    val inkMode: InkModeSource,
    /** Ámbito de la aplicación: las acciones del usuario no se cancelan al salir de una pantalla. */
    val scope: CoroutineScope,
    val versionName: String,
) {
    val actions: UiActions = UiActions(graph, scope, prefs)
    val covers: CoverLoader = CoverLoader(graph.covers)
}

val LocalUiEnv = staticCompositionLocalOf<UiEnv> { error("Falta LocalUiEnv") }
