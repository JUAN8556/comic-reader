package mx.jano.comics.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Modo vertical continuo: las páginas a lo ancho, una tras otra, en un LazyColumn.
 *
 * - Extracción perezosa: el LazyColumn solo compone lo visible; además se pide la ventana ±3 páginas.
 * - Cada página se decodifica al ancho de la pantalla (con tope de píxeles para las tiras muy largas).
 * - Progreso = primer elemento visible.
 * - Zoom: Telephoto no hace zoom de una lista que se desplaza, así que el pellizco es un `graphicsLayer` sencillo
 *   sobre la lista (1×-3×, desplazamiento horizontal acotado; el vertical lo hace la propia lista). Sin teselado:
 *   la nitidez al ampliar es la del mapa de bits a ancho de pantalla. Doble toque = volver a 1×.
 * - Toques: tercio central = superposición; laterales = desplazar el 85 % de la pantalla (sin animación).
 */
@Composable
internal fun VerticalReader(
    loader: PageLoader,
    pageCount: Int,
    startPage: Int,
    viewport: PxSize,
    controller: ReaderController,
    endPage: (@Composable () -> Unit)?,
    onTap: (Offset, IntSize) -> Unit,
    onPosition: (ReaderPosition) -> Unit,
) {
    val sizes by loader.sizes.collectAsState()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentOnTap by rememberUpdatedState(onTap)
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(listState) {
        snapshotFlow {
            val first = listState.firstVisibleItemIndex
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: first
            first to last
        }.collect { (firstRaw, lastRaw) ->
            if (pageCount <= 0) return@collect
            val first = firstRaw.coerceIn(0, pageCount - 1)
            val last = lastRaw.coerceIn(first, pageCount - 1)
            loader.setWindow(first, ExtractionPlanner.verticalWindow(first, last, pageCount))
            currentOnPosition(
                ReaderPosition(
                    page = first,
                    lastPage = last,
                    pageCount = pageCount,
                    spread = first,
                    spreadCount = pageCount,
                    // La página «Siguiente» es lo primero que se ve: ya no queda libro en pantalla.
                    atEnd = firstRaw >= pageCount,
                ),
            )
        }
    }

    DisposableEffect(controller, listState) {
        val navigator = object : ReaderNavigator {
            override suspend fun step(forward: Boolean) {
                val h = listState.layoutInfo.viewportSize.height.takeIf { it > 0 } ?: viewport.height
                val delta = h * 0.85f / scale.coerceAtLeast(1f)
                listState.scrollBy(if (forward) delta else -delta)
            }

            override suspend fun jumpToPage(page: Int) {
                listState.scrollToItem(page.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            }
        }
        controller.navigator = navigator
        onDispose { if (controller.navigator === navigator) controller.navigator = null }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            // Pellizco: se intercepta en la pasada inicial SOLO con dos dedos, para que un dedo siga desplazando la lista.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val newScale = (scale * zoom).coerceIn(1f, 3f)
                            val maxX = (newScale - 1f) * size.width / 2f
                            offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                            scale = newScale
                            if (newScale == 1f) offsetX = 0f
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offsetX = 0f
                    },
                    onTap = { currentOnTap(it, boxSize) },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    transformOrigin = TransformOrigin.Center
                },
        ) {
            items(count = pageCount, key = { it }) { index ->
                VerticalPageItem(index, pageCount, loader, sizes[index], viewport)
            }
            if (endPage != null) {
                item(key = "fin") {
                    Box(Modifier.fillMaxWidth().height(with(LocalDensity.current) { viewport.height.toDp() })) {
                        endPage()
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalPageItem(index: Int, pageCount: Int, loader: PageLoader, size: PageSize?, viewport: PxSize) {
    var attempt by remember { mutableIntStateOf(0) }
    val state by rememberPageFile(index, loader, attempt)
    val aspect = size?.aspect?.takeIf { it > 0f } ?: DEFAULT_PAGE.aspect
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            PageFileState.Loading -> PageSpinner()
            is PageFileState.Failed -> PageError(index + 1, s.message) {
                loader.forget(index)
                attempt++
            }
            is PageFileState.Ready -> {
                val context = LocalContext.current
                val width = viewport.width.coerceAtLeast(1)
                val shown = PxSize(width, (width / aspect).toInt().coerceAtLeast(1))
                val decode = FitResolver.decodeSize(
                    shown,
                    size?.let { PxSize(it.width, it.height) },
                    FitResolver.pixelBudget(viewport, halves = 1),
                )
                val request = remember(s.file, decode, attempt) {
                    ImageRequest.Builder(context).data(s.file).size(decode.width, decode.height).build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = ReaderTexts.pageDescription(index + 1, pageCount),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}
