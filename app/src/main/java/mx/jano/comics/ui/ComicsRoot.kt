package mx.jano.comics.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.collectLatest
import mx.jano.comics.probe.SondaScreen
import mx.jano.comics.reader.ReaderRoute
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.library.LibraryLogic
import mx.jano.comics.ui.library.LibraryScreen
import mx.jano.comics.ui.nav.NavStack
import mx.jano.comics.ui.nav.NavViewModel
import mx.jano.comics.ui.nav.Navigator
import mx.jano.comics.ui.nav.Screen
import mx.jano.comics.ui.now.NowScreen
import mx.jano.comics.ui.onboarding.OnboardingScreen
import mx.jano.comics.ui.search.SearchScreen
import mx.jano.comics.ui.series.SeriesScreen
import mx.jano.comics.ui.settings.SettingsScreen
import mx.jano.comics.ui.theme.LocalComicsLook

/**
 * Raíz de la interfaz: la pila de pantallas de [NavViewModel] (sin Navigation Compose), el gesto atrás predictivo
 * (sube un nivel en la biblioteca; en la raíz se desactiva y el sistema sale de la app con su animación), las tareas
 * de arranque y los avisos (Snackbar).
 */
@Composable
fun ComicsRoot() {
    val env = LocalUiEnv.current
    val graph = env.graph
    val look = LocalComicsLook.current
    val nav: NavViewModel = viewModel()
    val appSettings by graph.settings.app.collectAsStateWithLifecycle()
    val rootId = appSettings.rootFolderId
    val stack by nav.stack.collectAsStateWithLifecycle()

    nav.rootId = rootId
    nav.parentOf = remember(graph) { { id: String -> graph.library.folder(id)?.parentId } }

    LaunchedEffect(Unit) {
        nav.ensureStarted(if (rootId == null) Screen.Onboarding() else Screen.Library(rootId))
    }

    val top = stack.lastOrNull()
    val ready = Startup.ready(top)
    LaunchedEffect(rootId, ready) {
        if (rootId != null && Startup.shouldStart(rootId, top, nav.startedFor)) {
            nav.startedFor = rootId
            Startup.launch(env, rootId) {
                nav.startedFor = null
                nav.resetTo(Screen.Onboarding())
            }
        }
    }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(env) {
        env.actions.messages.collectLatest { snackbar.showSnackbar(it) }
    }

    var backProgress by remember { mutableFloatStateOf(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = NavStack.canGoBack(stack, rootId)) { events ->
        try {
            events.collect { e ->
                backProgress = e.progress
                backEdge = e.swipeEdge
            }
            nav.back()
        } finally {
            backProgress = 0f
        }
    }

    val holder = rememberSaveableStateHolder()
    val seen = remember { mutableSetOf<String>() }
    LaunchedEffect(stack) {
        val live = stack.mapTo(HashSet()) { it.key }
        (seen - live).forEach(holder::removeState)
        seen.retainAll(live)
        seen.addAll(live)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val p = backProgress
                    if (look.animations && p > 0f) {
                        val s = 1f - 0.08f * p
                        scaleX = s
                        scaleY = s
                        translationX = (if (backEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f) * p * 24.dp.toPx()
                        shape = RoundedCornerShape((20 * p).dp)
                        clip = true
                    }
                },
        ) {
            if (top != null) {
                if (look.animations) {
                    AnimatedContent(
                        targetState = top,
                        transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                        contentKey = { it.key },
                        label = "pantallas",
                    ) { screen -> ScreenSlot(holder, screen, nav) }
                } else {
                    ScreenSlot(holder, top, nav)
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

@Composable
private fun ScreenSlot(holder: SaveableStateHolder, screen: Screen, nav: NavViewModel) {
    holder.SaveableStateProvider(screen.key) {
        when (screen) {
            is Screen.Onboarding -> OnboardingScreen(
                changeRoot = screen.changeRoot,
                onDone = { id -> nav.resetTo(Screen.Library(id)) },
                onCancel = if (screen.changeRoot) nav::back else null,
            )
            is Screen.Library -> LibraryScreen(screen.folderId, nav)
            Screen.Now -> NowScreen(nav)
            Screen.Search -> SearchScreen(nav)
            Screen.Settings -> SettingsScreen(nav)
            Screen.Diagnostico -> DiagnosticoScreen(onBack = nav::back)
            is Screen.Reader -> ReaderHost(screen.bookId, nav)
            is Screen.Series -> SeriesScreen(screen.folderId, onBack = nav::back)
        }
    }
}

/** El lector del frente «ui-reader» para un libro del índice; si ya no está, un aviso con «atrás». */
@Composable
private fun ReaderHost(bookId: String, nav: Navigator) {
    val graph = LocalUiEnv.current.graph
    val index by graph.library.index.collectAsStateWithLifecycle()
    val indexLoading = rememberIndexLoading(graph.library)
    val book = remember(bookId, index) { graph.library.book(bookId) }
    if (book == null) {
        // Con la pila restaurada tras un arranque en frío, el índice puede no haber llegado aún: no está «ido».
        val text = if (index == null && indexLoading) UiStrings.LOADING else UiStrings.READER_BOOK_GONE
        Column(Modifier.fillMaxSize()) {
            ComicsTopBar(title = null, onBack = nav::back)
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
        }
        return
    }
    // Sin conexión y sin copia en la tablet no hay nada que leer: aviso en vez del lector (desde «Leyendo ahora» o la
    // búsqueda; la biblioteca ya avisa antes de navegar). Se decide al entrar para no echar a nadie a media lectura.
    val blocked = remember(book.id) {
        !graph.transfers.isOnline.value && !LibraryLogic.isOffline(graph.cache.availability(book))
    }
    if (blocked) {
        Column(Modifier.fillMaxSize()) {
            ComicsTopBar(title = UiStrings.LIB_OFFLINE_DIALOG_TITLE, onBack = nav::back)
            Text(UiStrings.libOfflineDialogBody(book.name), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(24.dp))
        }
        return
    }
    key(book.id) {
        ReaderRoute(
            graph = graph,
            book = book,
            onOpenBook = { next -> nav.replaceTop(Screen.Reader(next.id)) },
            onClose = nav::back,
        )
    }
}

/** La sonda de la tablet, conservada como «Diagnóstico» dentro de Ajustes. */
@Composable
private fun DiagnosticoScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ComicsTopBar(title = UiStrings.SET_DIAGNOSTICO, onBack = onBack)
        Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
            SondaScreen()
        }
    }
}
