package mx.jano.comics.ui.fakes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mx.jano.comics.AppGraph
import mx.jano.comics.ui.LocalAppGraph
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiEnv
import mx.jano.comics.ui.UiPrefsStore
import mx.jano.comics.ui.theme.ComicsTheme
import mx.jano.comics.ui.theme.InkModeSource

/** Modo NXTPAPER fijo para vistas previas y pruebas. */
class FakeInkMode(initial: Map<String, String?> = emptyMap()) : InkModeSource {
    val mutable = MutableStateFlow(initial)
    override val values: StateFlow<Map<String, String?>> = mutable
    override fun refresh() = Unit
}

/** Envuelve una vista previa con un grafo falso, preferencias en memoria y el tema. */
@Composable
fun PreviewEnv(graph: AppGraph = remember { FakeAppGraph() }, highContrast: Boolean = false, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val env = remember(graph) { UiEnv(graph, UiPrefsStore(null), FakeInkMode(), scope, "vista previa") }
    CompositionLocalProvider(LocalAppGraph provides graph, LocalUiEnv provides env) {
        ComicsTheme(highContrast = highContrast, content = content)
    }
}
