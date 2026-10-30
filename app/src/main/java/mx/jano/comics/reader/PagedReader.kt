package mx.jano.comics.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import me.saket.telephoto.zoomable.ZoomableState
import mx.jano.comics.settings.FitMode

/**
 * Modo paginado (una página o doble): HorizontalPager de PLIEGOS (reverseLayout en manga) con Telephoto en cada uno.
 *
 * El emparejamiento sale de [SpreadEngine] con los tamaños que el [PageLoader] va aprendiendo; cuando cambia (se
 * descubre una apaisada, se gira la tablet, se cambia el modo) el pager se recoloca en el pliego que contiene la
 * página que se estaba leyendo ([anchorPage]), sin animación. El slider y el progreso siguen en páginas.
 * Si hay [endPage], va como un pliego más al final («Siguiente: …»).
 */
@Composable
internal fun PagedReader(
    loader: PageLoader,
    pageCount: Int,
    layout: EffectiveLayout,
    fit: FitMode,
    rtl: Boolean,
    startPage: Int,
    viewport: PxSize,
    controller: ReaderController,
    endPage: (@Composable () -> Unit)?,
    onTap: (Offset, IntSize) -> Unit,
    onPosition: (ReaderPosition) -> Unit,
) {
    val sizes by loader.sizes.collectAsState()
    val computed = remember(pageCount, layout, sizes) { SpreadEngine.build(pageCount, layout, sizes) }
    var shown by remember { mutableStateOf(computed) }
    var anchorPage by remember { mutableIntStateOf(startPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))) }
    var repairing by remember { mutableStateOf(false) }
    val hasEnd = endPage != null
    val pagerState = rememberPagerState(initialPage = shown.spreadOf(anchorPage)) { shown.size + if (hasEnd) 1 else 0 }
    val currentOnPosition by rememberUpdatedState(onPosition)
    val currentRtl by rememberUpdatedState(rtl)

    // Nuevo emparejamiento: recolocar en el pliego de la página ancla (o seguir en «Siguiente» si se estaba ahí).
    LaunchedEffect(computed) {
        if (!computed.samePairingAs(shown)) {
            repairing = true
            try {
                val wasAtEnd = pagerState.currentPage >= shown.size
                val target = if (wasAtEnd && hasEnd) computed.size else computed.spreadOf(anchorPage)
                ReaderTrace.log("pliegos rehechos: $computed")
                shown = computed
                pagerState.scrollToPage(target)
            } finally {
                repairing = false
            }
        }
    }

    // Posición (para el progreso y la superposición) cuando el pager se asienta.
    LaunchedEffect(pagerState) {
        snapshotFlow { Triple(pagerState.settledPage, shown, repairing) }.collect { (settled, map, rep) ->
            if (rep) return@collect
            controller.endAlignRequest?.let { if (it != settled) controller.endAlignRequest = null }
            if (map.isEmpty) return@collect
            if (settled < map.size) {
                anchorPage = map.firstPageOf(settled)
                ReaderTrace.log("pliego ${settled + 1} de ${map.size} asentado (págs. ${map[settled].pages.joinToString("-") { (it + 1).toString() }})")
                currentOnPosition(
                    ReaderPosition(
                        page = map.firstPageOf(settled),
                        lastPage = map.lastPageOf(settled),
                        pageCount = pageCount,
                        spread = settled,
                        spreadCount = map.size,
                    ),
                )
            } else {
                val last = map.size - 1
                currentOnPosition(
                    ReaderPosition(
                        page = map.firstPageOf(last),
                        lastPage = pageCount - 1,
                        pageCount = pageCount,
                        spread = last,
                        spreadCount = map.size,
                        atEnd = true,
                    ),
                )
            }
        }
    }

    // Ventana de extracción: el pliego anterior y dos por delante, en cuanto el pager se mueve.
    LaunchedEffect(pagerState, loader) {
        snapshotFlow { pagerState.currentPage to shown }.collect { (current, map) ->
            if (map.isEmpty) return@collect
            val s = current.coerceIn(0, map.size - 1)
            loader.setWindow(map.firstPageOf(s), ExtractionPlanner.pagedWindow(map, s))
        }
    }

    val zoomRegistry = remember { HashMap<Int, ZoomableState>() }
    DisposableEffect(controller, pagerState) {
        val navigator = object : ReaderNavigator {
            override suspend fun step(forward: Boolean) {
                val current = pagerState.currentPage
                val zoom = zoomRegistry[current]
                if (current < shown.size && zoom != null && zoom.stepWithin(forward, currentRtl)) return
                val target = current + if (forward) 1 else -1
                if (target !in 0 until pagerState.pageCount) return
                controller.endAlignRequest = if (!forward && target < shown.size) target else null
                pagerState.scrollToPage(target)
            }

            override suspend fun jumpToPage(page: Int) {
                controller.endAlignRequest = null
                pagerState.scrollToPage(shown.spreadOf(page))
            }
        }
        controller.navigator = navigator
        onDispose { if (controller.navigator === navigator) controller.navigator = null }
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize(),
        beyondViewportPageCount = 1,
        reverseLayout = rtl,
        key = { index -> if (index < shown.size) shown[index].first else END_PAGE_KEY },
    ) { index ->
        val map = shown
        if (index >= map.size) {
            endPage?.invoke()
        } else {
            SpreadPage(
                spreadIndex = index,
                spread = map[index],
                pageCount = pageCount,
                loader = loader,
                sizes = sizes,
                fit = fit,
                rtl = rtl,
                viewport = viewport,
                isSettled = pagerState.settledPage == index,
                controller = controller,
                zoomRegistry = zoomRegistry,
                onTap = onTap,
            )
        }
    }
}

private const val END_PAGE_KEY = -1
