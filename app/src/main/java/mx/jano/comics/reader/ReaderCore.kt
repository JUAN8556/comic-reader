package mx.jano.comics.reader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import mx.jano.comics.archive.ArchiveKind
import mx.jano.comics.archive.ComicArchive
import mx.jano.comics.settings.ReaderSettings
import java.io.File

/** El barrido de tamaños (doble página) solo en libros de un tamaño razonable: un ómnibus de 900 páginas no se extrae entero. */
internal const val SWEEP_MAX_PAGES = 400

/**
 * Núcleo del lector, compartido por [ReaderRoute] (la app) y [ReaderScreen] (la sonda): un archivo ya abierto, los
 * ajustes de lectura y unos callbacks. Elige el modo (paginado de una o dos páginas, o vertical continuo), pone la
 * superposición, las zonas de toque, las teclas de volumen, la ventana inmersiva y la guarda de los bordes (UI-4).
 *
 * «Atrás»: sin la superposición, la abre y avisa «Atrás otra vez para salir»; con ella abierta, cierra. Así un
 * deslizamiento accidental desde el borde de la pantalla no cierra el libro.
 */
@Composable
internal fun ReaderCore(
    archive: ComicArchive,
    pageCacheDir: File,
    title: String,
    startPage: Int,
    settings: ReaderSettings,
    rtl: Boolean,
    palette: ReaderPalette,
    onUpdateSettings: ((ReaderSettings) -> ReaderSettings) -> Unit,
    manga: MangaMenuModel?,
    onPosition: (ReaderPosition) -> Unit,
    endPage: (@Composable () -> Unit)?,
    onClose: () -> Unit,
) {
    val loader = remember(archive, pageCacheDir) {
        // El foco arranca en la página de entrada: lo primero que se extrae es lo que se va a ver.
        PageLoader(archive, pageCacheDir).also { it.setWindow(startPage, listOf(startPage)) }
    }
    DisposableEffect(loader) { onDispose { loader.close() } }
    val pageCount = loader.pageCount
    val controller = remember(loader) { ReaderController() }
    val scope = rememberCoroutineScope()
    var overlayVisible by rememberSaveable { mutableStateOf(false) }
    var backHint by remember { mutableStateOf(false) }
    val first = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
    var position by remember(loader) { mutableStateOf(ReaderPosition(first, first, pageCount)) }
    val currentOnPosition by rememberUpdatedState(onPosition)

    ReaderWindowEffect(immersive = !overlayVisible, keepScreenOn = settings.keepScreenOn)

    BackHandler {
        if (overlayVisible) {
            onClose()
        } else {
            overlayVisible = true
            backHint = true
        }
    }
    LaunchedEffect(overlayVisible) { if (!overlayVisible) backHint = false }

    // Teclas de volumen: +1 = siguiente, -1 = anterior (ya en el sentido de lectura; MainActivity las reenvía).
    VolumeKeysEffect(enabled = settings.volumeKeys) { delta -> controller.navigator?.step(forward = delta > 0) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .edgeBackGuard(),
    ) {
        val viewport = PxSize(constraints.maxWidth, constraints.maxHeight)
        val landscape = constraints.maxWidth > constraints.maxHeight
        val effective = SpreadEngine.effectiveLayout(settings.layout, landscape)
        val sweep = effective == EffectiveLayout.DOUBLE && archive.kind != ArchiveKind.PDF && pageCount <= SWEEP_MAX_PAGES
        SideEffect { loader.sweep = sweep }

        val zonesRtl = rtl && effective != EffectiveLayout.VERTICAL
        val onTap: (Offset, IntSize) -> Unit = { offset, size ->
            val action = if (settings.tapZones) TapZones.actionFor(offset.x, size.width.toFloat(), zonesRtl) else TapAction.TOGGLE_OVERLAY
            when (action) {
                TapAction.TOGGLE_OVERLAY -> overlayVisible = !overlayVisible
                TapAction.NEXT -> scope.launch { controller.navigator?.step(forward = true) }
                TapAction.PREVIOUS -> scope.launch { controller.navigator?.step(forward = false) }
            }
        }
        val report: (ReaderPosition) -> Unit = {
            position = it
            currentOnPosition(it)
        }

        if (pageCount <= 0) {
            EmptyArchive(onClose)
        } else {
            val vertical = effective == EffectiveLayout.VERTICAL
            key(vertical) {
                // Al cambiar de modo se entra por la página que se estaba leyendo.
                val entryPage = remember { position.page }
                if (vertical) {
                    VerticalReader(
                        loader = loader,
                        pageCount = pageCount,
                        startPage = entryPage,
                        viewport = viewport,
                        controller = controller,
                        endPage = endPage,
                        onTap = onTap,
                        onPosition = report,
                    )
                } else {
                    PagedReader(
                        loader = loader,
                        pageCount = pageCount,
                        layout = effective,
                        fit = settings.fit,
                        rtl = rtl,
                        startPage = entryPage,
                        viewport = viewport,
                        controller = controller,
                        endPage = endPage,
                        onTap = onTap,
                        onPosition = report,
                    )
                }
            }
            if (overlayVisible) {
                ReaderOverlay(
                    title = title,
                    position = position,
                    layout = effective,
                    fit = settings.fit,
                    pageLayout = settings.layout,
                    rtl = rtl,
                    manga = manga,
                    showBackHint = backHint,
                    palette = palette,
                    onClose = onClose,
                    onFit = { f -> onUpdateSettings { it.copy(fit = f) } },
                    onLayout = { l -> onUpdateSettings { it.copy(layout = l) } },
                    onSeek = { page -> scope.launch { controller.navigator?.jumpToPage(page) } },
                )
            }
        }
    }
}

/**
 * Pone [VolumeKeyBus.consume] mientras el lector está en pantalla (y lo quita al salir) y escucha sus eventos.
 * Si se abre otro libro encima (N+1), el lector que sale no le quita las teclas al que entra.
 */
@Composable
private fun VolumeKeysEffect(enabled: Boolean, onDelta: suspend (Int) -> Unit) {
    val currentOnDelta by rememberUpdatedState(onDelta)
    DisposableEffect(enabled) {
        val token = Any()
        VolumeKeyOwner.claim(token, enabled)
        onDispose { VolumeKeyOwner.release(token) }
    }
    LaunchedEffect(Unit) {
        VolumeKeyBus.events.collect { delta -> if (VolumeKeyBus.consume) currentOnDelta(delta) }
    }
}

/** Quién tiene ahora las teclas de volumen (el último lector que las pidió). */
internal object VolumeKeyOwner {
    private var owner: Any? = null

    @Synchronized fun claim(token: Any, enabled: Boolean) {
        owner = token
        VolumeKeyBus.consume = enabled
    }

    @Synchronized fun release(token: Any) {
        if (owner === token) {
            owner = null
            VolumeKeyBus.consume = false
        }
    }
}

@Composable
internal fun EmptyArchive(onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(ReaderTexts.EMPTY, color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onClose) { Text(ReaderTexts.CLOSE, color = Color.White) }
    }
}
