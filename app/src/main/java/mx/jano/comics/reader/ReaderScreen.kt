package mx.jano.comics.reader

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import mx.jano.comics.archive.ComicArchive
import java.io.File
import kotlin.math.roundToInt

/**
 * CONTRATO COMÚN. Lector de un cómic ya abierto: HorizontalPager (reverseLayout si [rtl]) + zoom con Telephoto,
 * páginas extraídas a [pageCacheDir] y cargadas con Coil desde fichero (teselado para escaneos grandes).
 * Inmersivo (barras del sistema ocultas), FLAG_KEEP_SCREEN_ON, atrás predictivo → [onClose].
 *
 * Gestos: Telephoto consume el arrastre mientras hay zoom y lo cede al pager al llegar al borde de la imagen
 * (es su comportamiento por defecto dentro de un pager; la receta oficial solo añade resetear el zoom de las
 * páginas que dejan de ser la asentada). Toques: tercio central = superposición con «Pág. X de Y» y barra para
 * saltar; tercios laterales = anterior/siguiente (invertidos en manga). Doble toque = zoom (Telephoto).
 * El paso de página por toque es instantáneo (scrollToPage): en la pantalla de papel una animación solo deja estelas.
 */
@Composable
fun ReaderScreen(
    archive: ComicArchive,
    pageCacheDir: File,
    startPage: Int = 0,
    rtl: Boolean = false,
    onClose: () -> Unit,
) {
    val pageCount = archive.pageCount
    val loader = remember(archive, pageCacheDir) { PageLoader(archive, pageCacheDir) }
    DisposableEffect(loader) { onDispose { loader.close() } }

    ImmersiveReaderEffect()

    val pagerState = rememberPagerState(
        initialPage = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)),
    ) { pageCount }
    var overlayVisible by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Atrás: primero cierra la superposición; después sale del lector.
    BackHandler {
        if (overlayVisible) overlayVisible = false else onClose()
    }

    val onTap: (Offset, IntSize) -> Unit = { offset, size ->
        val action = TapZones.actionFor(offset.x, size.width.toFloat(), rtl)
        if (action == TapAction.TOGGLE_OVERLAY) {
            overlayVisible = !overlayVisible
        } else {
            TapZones.targetPage(action, pagerState.currentPage, pageCount)?.let { target ->
                scope.launch { pagerState.scrollToPage(target) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (pageCount <= 0) {
            EmptyArchive(onClose)
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                reverseLayout = rtl,
            ) { index ->
                ReaderPage(
                    index = index,
                    pageCount = pageCount,
                    loader = loader,
                    isSettledPage = pagerState.settledPage == index,
                    onTap = onTap,
                )
            }
            if (overlayVisible) {
                ReaderOverlay(
                    pagerState = pagerState,
                    pageCount = pageCount,
                    rtl = rtl,
                    onClose = onClose,
                )
            }
        }
    }
}

/** Oculta las barras del sistema (vuelven con un deslizamiento, transitorias) y mantiene la pantalla encendida. */
@Composable
private fun ImmersiveReaderEffect() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val window = activity?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val previousBehavior = controller.systemBarsBehavior
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                controller.show(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = previousBehavior
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }
}

private sealed interface PageState {
    data object Loading : PageState
    data class Ready(val file: File) : PageState
    data class Failed(val message: String) : PageState
}

@Composable
private fun ReaderPage(
    index: Int,
    pageCount: Int,
    loader: PageLoader,
    isSettledPage: Boolean,
    onTap: (Offset, IntSize) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by produceState<PageState>(initialValue = PageState.Loading, index, attempt) {
        value = PageState.Loading
        value = try {
            PageState.Ready(loader.page(index))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PageState.Failed(describe(t))
        }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val tapAt: (Offset) -> Unit = { currentOnTap(it, size) }

    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maximum = ZoomLimit(3f)))
    val imageState = rememberZoomableImageState(zoomableState)
    var decodeError by remember(index, attempt) { mutableStateOf<String?>(null) }
    val retry: () -> Unit = {
        loader.forget(index)
        attempt++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            PageState.Loading -> TapSurface(tapAt) { PageSpinner() }
            is PageState.Failed -> TapSurface(tapAt) { PageError(index + 1, s.message, retry) }
            is PageState.Ready -> {
                val context = LocalContext.current
                val request = remember(s.file, attempt) {
                    ImageRequest.Builder(context)
                        .data(s.file)
                        .listener(onError = { _, result -> decodeError = describe(result.throwable) })
                        .build()
                }
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = ReaderTexts.pageDescription(index + 1, pageCount),
                    modifier = Modifier.fillMaxSize(),
                    state = imageState,
                    contentScale = ContentScale.Fit,
                    onClick = tapAt,
                )
                val error = decodeError
                if (error != null) {
                    PageError(index + 1, error, retry)
                } else if (!imageState.isImageDisplayed) {
                    PageSpinner()
                }
            }
        }
    }

    // Receta de Telephoto para pagers: la página que deja de ser la asentada vuelve a su zoom inicial,
    // así al regresar a ella no aparece ampliada.
    if (!isSettledPage) {
        LaunchedEffect(Unit) {
            zoomableState.resetZoom(animationSpec = SnapSpec())
        }
    }
}

/** Superficie que recibe toques mientras no hay imagen (cargando o error), para que los tercios sigan funcionando. */
@Composable
private fun TapSurface(onTap: (Offset) -> Unit, content: @Composable () -> Unit) {
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { currentOnTap(it) }) },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** Indicador discreto: pequeño y gris, con el texto para lectores de pantalla. */
@Composable
private fun PageSpinner() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = Color(0xFF8A8A8A),
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.height(8.dp))
        Text(ReaderTexts.LOADING, color = Color(0xFF8A8A8A), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun PageError(page: Int, message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            ReaderTexts.pageError(page),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = Color(0xFFBDBDBD),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) { Text(ReaderTexts.RETRY, color = Color.White) }
    }
}

@Composable
private fun EmptyArchive(onClose: () -> Unit) {
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

/**
 * Superposición: arriba «Pág. X de Y» y Cerrar; abajo la barra para saltar. Las dos bandas se tragan los toques
 * para no pasar página por accidente; el hueco del centro deja pasar los toques al pager (centro = ocultar).
 */
@Composable
private fun ReaderOverlay(
    pagerState: PagerState,
    pageCount: Int,
    rtl: Boolean,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownPage = (dragging?.roundToInt() ?: pagerState.currentPage) + 1
    val band = Modifier
        .fillMaxWidth()
        .background(Color.Black.copy(alpha = 0.85f))
        .pointerInput(Unit) { detectTapGestures { } }
        .windowInsetsPadding(WindowInsets.displayCutout)
        .padding(horizontal = 16.dp, vertical = 8.dp)

    Column(Modifier.fillMaxSize()) {
        Row(modifier = band, verticalAlignment = Alignment.CenterVertically) {
            Text(
                ReaderTexts.pageOf(shownPage, pageCount),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text(ReaderTexts.CLOSE, color = Color.White) }
        }
        Spacer(Modifier.weight(1f))
        Column(modifier = band) {
            Text(
                if (rtl) ReaderTexts.DIRECTION_RTL else ReaderTexts.DIRECTION_LTR,
                color = Color(0xFFBDBDBD),
                style = MaterialTheme.typography.labelMedium,
            )
            if (pageCount > 1) {
                // En manga la barra también va de derecha a izquierda: la página 1 queda a la derecha.
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    Slider(
                        value = dragging ?: pagerState.currentPage.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = (dragging ?: return@Slider).roundToInt().coerceIn(0, pageCount - 1)
                            dragging = null
                            scope.launch { pagerState.scrollToPage(target) }
                        },
                        valueRange = 0f..(pageCount - 1).toFloat(),
                    )
                }
            }
        }
    }
}

private fun describe(t: Throwable): String = "${t::class.java.simpleName}: ${t.message ?: "(sin mensaje)"}"
