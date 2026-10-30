package mx.jano.comics.reader

import android.os.SystemClock
import androidx.compose.animation.core.SnapSpec
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.Viewport
import me.saket.telephoto.zoomable.ZoomLimit
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.ZoomableState
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.spatial.CoordinateSpace
import me.saket.telephoto.zoomable.zoomable
import mx.jano.comics.settings.FitMode
import java.io.File
import kotlin.math.min

internal fun ScaleKind.toContentScale(): ContentScale = when (this) {
    ScaleKind.FIT -> ContentScale.Fit
    ScaleKind.FILL_WIDTH -> ContentScale.FillWidth
    ScaleKind.FILL_HEIGHT -> ContentScale.FillHeight
}

internal fun AlignKind.toAlignment(): Alignment = when (this) {
    AlignKind.CENTER -> Alignment.Center
    AlignKind.TOP_CENTER -> Alignment.TopCenter
    AlignKind.CENTER_LEFT -> AbsoluteAlignment.CenterLeft
    AlignKind.CENTER_RIGHT -> AbsoluteAlignment.CenterRight
}

/** Zoom máximo: 3× (como en la sonda). */
internal val ReaderZoomSpec = ZoomSpec(maximum = ZoomLimit(3f))

internal sealed interface PageFileState {
    data object Loading : PageFileState
    data class Ready(val file: File) : PageFileState
    data class Failed(val message: String) : PageFileState
}

/** Extrae (o recoge de disco) la página [index]; se vuelve a pedir cuando cambia [attempt]. */
@Composable
internal fun rememberPageFile(index: Int, loader: PageLoader, attempt: Int): State<PageFileState> =
    produceState<PageFileState>(initialValue = loader.pageIfReady(index)?.let { PageFileState.Ready(it) } ?: PageFileState.Loading, index, loader, attempt) {
        if (value is PageFileState.Ready) return@produceState
        value = PageFileState.Loading
        value = try {
            PageFileState.Ready(loader.page(index))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PageFileState.Failed(describe(t))
        }
    }

/**
 * Una página del pager (un pliego): su estado de zoom, la receta de Telephoto para pagers (la página que deja de ser
 * la asentada vuelve a su zoom inicial), el «mostrar por el final» al llegar retrocediendo y el reinicio del zoom
 * cuando cambia el ajuste.
 */
@Composable
internal fun SpreadPage(
    spreadIndex: Int,
    spread: Spread,
    pageCount: Int,
    loader: PageLoader,
    firstSize: PageSize?,
    secondSize: PageSize?,
    fit: FitMode,
    rtl: Boolean,
    viewport: PxSize,
    isSettled: Boolean,
    controller: ReaderController,
    zoomRegistry: MutableMap<Int, ZoomableState>,
    onTap: (Offset, IntSize) -> Unit,
) {
    val zoomState = rememberZoomableState(zoomSpec = ReaderZoomSpec)
    DisposableEffect(spreadIndex, zoomState) {
        zoomRegistry[spreadIndex] = zoomState
        onDispose { if (zoomRegistry[spreadIndex] === zoomState) zoomRegistry.remove(spreadIndex) }
    }

    // Al cambiar el ajuste, el zoom del usuario vuelve a la escala base nueva.
    var lastFit by remember { mutableStateOf(fit) }
    LaunchedEffect(fit) {
        if (fit != lastFit) {
            lastFit = fit
            zoomState.resetZoom(animationSpec = SnapSpec())
        }
    }

    // Se llegó retrocediendo (toque o volumen): se enseña el final del pliego (abajo; en manga, abajo a la izquierda).
    val alignEnd = controller.endAlignRequest == spreadIndex
    LaunchedEffect(alignEnd, isSettled) {
        if (alignEnd && isSettled) {
            zoomState.panBy(Offset(if (rtl) 100_000f else -100_000f, -100_000f), SnapSpec())
            if (controller.endAlignRequest == spreadIndex) controller.endAlignRequest = null
        }
    }

    if (spread.isDouble) {
        DoubleSpreadContent(spread, pageCount, loader, firstSize, secondSize, fit, rtl, viewport, zoomState, isSettled, onTap)
    } else {
        val size = firstSize
        val spec = FitResolver.resolve(fit, rtl, size?.let { PxSize(it.width, it.height) }, viewport)
        SinglePageContent(spread.first, pageCount, loader, spec, zoomState, isSettled, onTap)
    }

    if (!isSettled) {
        LaunchedEffect(Unit) { zoomState.resetZoom(animationSpec = SnapSpec()) }
    }
}

/** Una página sola: Coil + Telephoto con teselado (los escaneos grandes no se decodifican enteros). */
@Composable
private fun SinglePageContent(
    index: Int,
    pageCount: Int,
    loader: PageLoader,
    fit: FitSpec,
    zoomState: ZoomableState,
    isSettled: Boolean,
    onTap: (Offset, IntSize) -> Unit,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val composedAt = remember(index, attempt) { SystemClock.elapsedRealtime() }
    val state by rememberPageFile(index, loader, attempt)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val tapAt: (Offset) -> Unit = { currentOnTap(it, size) }
    val imageState = rememberZoomableImageState(zoomState)
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
            PageFileState.Loading -> TapSurface(tapAt) { PageSpinner() }
            is PageFileState.Failed -> TapSurface(tapAt) { PageError(index + 1, s.message, retry) }
            is PageFileState.Ready -> {
                val context = LocalContext.current
                val request = remember(s.file, attempt) {
                    ImageRequest.Builder(context)
                        .data(s.file)
                        .listener(onError = { _, result ->
                            decodeError = describe(result.throwable)
                            ReaderTrace.log("pág. ${index + 1}: ERROR al decodificar: ${describe(result.throwable)}")
                        })
                        .build()
                }
                ZoomableAsyncImage(
                    model = request,
                    contentDescription = ReaderTexts.pageDescription(index + 1, pageCount),
                    modifier = Modifier.fillMaxSize(),
                    state = imageState,
                    alignment = fit.align.toAlignment(),
                    contentScale = fit.scale.toContentScale(),
                    onClick = tapAt,
                )
                val displayed = imageState.isImageDisplayed
                LaunchedEffect(s.file, attempt, displayed) {
                    if (displayed) {
                        val ms = SystemClock.elapsedRealtime() - composedAt
                        val where = if (isSettled) "en pantalla" else "precargada"
                        ReaderTrace.log("pág. ${index + 1} dibujada ($where) $ms ms después de componerse")
                    }
                }
                val error = decodeError
                if (error != null) {
                    PageError(index + 1, error, retry)
                } else if (!imageState.isImageDisplayed) {
                    PageSpinner()
                }
            }
        }
    }
}

/**
 * Pliego doble: dos imágenes de Coil (cada una decodificada a su mitad del pliego en pantalla, nunca a su tamaño
 * original) pintadas como un cuadro con [SpreadPainter], y `Modifier.zoomable` encima. El zoom de un pliego no tesela:
 * para leer de cerca una viñeta pequeña conviene «Una página», que sí lo hace.
 */
@Composable
private fun DoubleSpreadContent(
    spread: Spread,
    pageCount: Int,
    loader: PageLoader,
    firstSize: PageSize?,
    secondSize: PageSize?,
    fitMode: FitMode,
    rtl: Boolean,
    viewport: PxSize,
    zoomState: ZoomableState,
    isSettled: Boolean,
    onTap: (Offset, IntSize) -> Unit,
) {
    val screen = spread.leftToRight(rtl)
    val leftIndex = screen[0]
    val rightIndex = screen[1]
    var attempt by remember { mutableIntStateOf(0) }
    val composedAt = remember(spread, attempt) { SystemClock.elapsedRealtime() }
    val leftState by rememberPageFile(leftIndex, loader, attempt)
    val rightState by rememberPageFile(rightIndex, loader, attempt)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val tapAt: (Offset) -> Unit = { currentOnTap(it, size) }
    val retry: () -> Unit = {
        loader.forget(leftIndex)
        loader.forget(rightIndex)
        attempt++
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it },
        contentAlignment = Alignment.Center,
    ) {
        val l = leftState
        val r = rightState
        when {
            l is PageFileState.Failed -> TapSurface(tapAt) { PageError(leftIndex + 1, l.message, retry) }
            r is PageFileState.Failed -> TapSurface(tapAt) { PageError(rightIndex + 1, r.message, retry) }
            l is PageFileState.Ready && r is PageFileState.Ready -> {
                // «Ready» implica que el cargador ya publicó el tamaño (lo hace antes de entregar el fichero); el mapa
                // recogido por la composición puede ir un fotograma por detrás: se mira también el valor actual.
                fun sizeOf(page: Int): PageSize =
                    (if (page == spread.first) firstSize else secondSize) ?: loader.sizes.value[page] ?: DEFAULT_PAGE
                val leftSize = sizeOf(leftIndex)
                val rightSize = sizeOf(rightIndex)
                val geometry = remember(leftSize, rightSize) {
                    FitResolver.spreadContent(PxSize(leftSize.width, leftSize.height), PxSize(rightSize.width, rightSize.height))
                }
                val content = PxSize(geometry.width.toInt().coerceAtLeast(1), geometry.height.toInt().coerceAtLeast(1))
                val fit = FitResolver.resolve(fitMode, rtl, content, viewport)
                val shown = FitResolver.displayedSize(fit.scale, content, viewport)
                val k = shown.height / geometry.height
                val budget = FitResolver.pixelBudget(viewport, halves = 2)
                val leftDecode = FitResolver.decodeSize(
                    PxSize((geometry.leftWidth * k).toInt(), shown.height), PxSize(leftSize.width, leftSize.height), budget,
                )
                val rightDecode = FitResolver.decodeSize(
                    PxSize((geometry.rightWidth * k).toInt(), shown.height), PxSize(rightSize.width, rightSize.height), budget,
                )
                val context = LocalContext.current
                val leftPainter = rememberAsyncImagePainter(
                    model = remember(l.file, leftDecode) {
                        ImageRequest.Builder(context).data(l.file).size(leftDecode.width, leftDecode.height).build()
                    },
                )
                val rightPainter = rememberAsyncImagePainter(
                    model = remember(r.file, rightDecode) {
                        ImageRequest.Builder(context).data(r.file).size(rightDecode.width, rightDecode.height).build()
                    },
                )
                val painter = remember(leftPainter, rightPainter, geometry) { SpreadPainter(leftPainter, rightPainter, geometry) }
                zoomState.contentScale = fit.scale.toContentScale()
                zoomState.contentAlignment = fit.align.toAlignment()
                zoomState.setContentLocation(ZoomableContentLocation.scaledInsideAndCenterAligned(painter.intrinsicSize))
                val first = spread.first + 1
                Image(
                    painter = painter,
                    contentDescription = ReaderTexts.spreadDescription(first, first + 1, pageCount),
                    modifier = Modifier
                        .fillMaxSize()
                        .zoomable(state = zoomState, onClick = tapAt),
                    alignment = Alignment.Center,
                    contentScale = ContentScale.Inside,
                )
                val ls by leftPainter.state.collectAsState()
                val rs by rightPainter.state.collectAsState()
                val failure = (ls as? AsyncImagePainter.State.Error) ?: (rs as? AsyncImagePainter.State.Error)
                val bothShown = ls is AsyncImagePainter.State.Success && rs is AsyncImagePainter.State.Success
                LaunchedEffect(bothShown) {
                    if (bothShown) {
                        val ms = SystemClock.elapsedRealtime() - composedAt
                        val where = if (isSettled) "en pantalla" else "precargado"
                        ReaderTrace.log("pliego ${spread.first + 1}-${spread.last + 1} dibujado ($where) $ms ms después de componerse")
                    }
                }
                when {
                    failure != null -> {
                        val bad = if (ls is AsyncImagePainter.State.Error) leftIndex else rightIndex
                        PageError(bad + 1, describe(failure.result.throwable), retry)
                    }
                    !bothShown -> PageSpinner()
                }
            }
            else -> TapSurface(tapAt) { PageSpinner() }
        }
    }
}

/** Tamaño supuesto de una página cuya cabecera no se pudo leer (proporción de un cómic americano). */
internal val DEFAULT_PAGE = PageSize(1988, 3056)

/**
 * Pasa dentro de la página antes que de pliego (lectura en «Z»: en occidental, a la derecha y luego abajo volviendo
 * a la izquierda; en manga, a la izquierda y luego abajo volviendo a la derecha). Devuelve false si el contenido no
 * desborda en esa dirección (entonces toca cambiar de pliego). Sin animación: en la pantalla de papel solo deja estelas.
 */
@OptIn(ExperimentalTelephotoApi::class)
internal suspend fun ZoomableState.stepWithin(forward: Boolean, rtl: Boolean): Boolean {
    val system = coordinateSystem
    val viewport = system.viewportSize
    if (viewport.width <= 0f || viewport.height <= 0f) return false
    val bounds = with(system) { system.contentBounds(clipToViewport = false).rectIn(CoordinateSpace.Viewport) }
    if (bounds.width <= 0f || bounds.height <= 0f) return false
    val eps = 1.5f
    val below = (bounds.bottom - viewport.height).coerceAtLeast(0f)
    val above = (-bounds.top).coerceAtLeast(0f)
    val toRight = (bounds.right - viewport.width).coerceAtLeast(0f)
    val toLeft = (-bounds.left).coerceAtLeast(0f)
    val stepY = viewport.height * 0.85f
    val stepX = viewport.width * 0.85f
    // Desplazar el contenido: x negativo = se ve lo que había a la derecha; y negativo = se ve lo de abajo.
    val pan: Offset? = if (forward) {
        when {
            !rtl && toRight > eps -> Offset(-min(toRight, stepX), 0f)
            rtl && toLeft > eps -> Offset(min(toLeft, stepX), 0f)
            below > eps -> Offset(if (rtl) -toRight else toLeft, -min(below, stepY))
            else -> null
        }
    } else {
        when {
            !rtl && toLeft > eps -> Offset(min(toLeft, stepX), 0f)
            rtl && toRight > eps -> Offset(-min(toRight, stepX), 0f)
            above > eps -> Offset(if (rtl) toLeft else -toRight, min(above, stepY))
            else -> null
        }
    }
    if (pan == null) return false
    panBy(pan, SnapSpec())
    return true
}

/** Superficie que recibe toques mientras no hay imagen (cargando o error), para que los tercios sigan funcionando. */
@Composable
internal fun TapSurface(onTap: (Offset) -> Unit, content: @Composable () -> Unit) {
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

/** Indicador discreto: pequeño y gris, con el texto para lectores de pantalla (y para la pantalla en gris). */
@Composable
internal fun PageSpinner() {
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
internal fun PageError(page: Int, message: String, onRetry: () -> Unit) {
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

internal fun describe(t: Throwable): String = "${t::class.java.simpleName}: ${t.message ?: "(sin mensaje)"}"
