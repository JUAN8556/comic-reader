package mx.jano.comics.reader

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.jano.comics.AppGraph
import mx.jano.comics.archive.ComicArchive
import mx.jano.comics.archive.ComicArchives
import mx.jano.comics.cache.Availability
import mx.jano.comics.cache.DownloadProgress
import mx.jano.comics.library.Book
import mx.jano.comics.library.Naming
import mx.jano.comics.settings.ReaderSettings
import mx.jano.comics.settings.ThemeMode
import java.io.File

/**
 * CONTRATO COMÚN del MVP. Pantalla completa de lectura de un libro de la biblioteca: asegura la copia local
 * (progreso de descarga visible), abre el archivo, aplica ajustes (ajuste, pliegos, vertical, RTL efectivo),
 * guarda el progreso con debounce, al terminar ofrece «Siguiente: N+1» (prefetch vía graph.transfers) y
 * excluye los bordes del gesto «atrás» del sistema. La rellena el frente «ui-reader».
 *
 * Estados: Descargando (cache.ensureLocal; anillo, MB, KB/s, Cancelar → [onClose]) → Abriendo (ComicArchives.open en
 * IO) → Leyendo ([ReaderCore]) → Fin (el último pliego del lector: «Siguiente: …» con «Leer» → [onOpenBook] y
 * «Volver» → [onClose]). Si el anfitrión cambia [book] sin desmontar la ruta, todo se rehace para el libro nuevo.
 */
@Composable
fun ReaderRoute(
    graph: AppGraph,
    book: Book,
    onOpenBook: (Book) -> Unit,   // para saltar al siguiente número
    onClose: () -> Unit,
) {
    key(book.id) {
        ReaderRouteContent(graph, book, onOpenBook, onClose)
    }
}

/** Ámbito que sobrevive a la pantalla: el último guardado de progreso, cerrar el archivo, aplicar el tope de caché. */
internal object ReaderScope : CoroutineScope by CoroutineScope(SupervisorJob() + Dispatchers.IO)

private sealed interface RouteState {
    data class Downloading(val progress: DownloadProgress?) : RouteState
    data object Opening : RouteState
    class Reading(val archive: ComicArchive) : RouteState
    data class Failed(val headline: String, val message: String, val hint: String?) : RouteState
}

@Composable
private fun ReaderRouteContent(
    graph: AppGraph,
    book: Book,
    onOpenBook: (Book) -> Unit,
    onClose: () -> Unit,
) {
    val app by graph.settings.app.collectAsState()
    val reader by graph.settings.reader.collectAsState()
    val palette = ReaderPalette.of(highContrastFor(app.theme))
    val scope = rememberCoroutineScope()

    val next = remember(book.id) { runCatching { graph.library.nextAfter(book) }.getOrNull() }
    val title = remember(book.id) { displayName(graph, book) }
    val cover = remember(book.id) { runCatching { graph.covers.coverFile(book) }.getOrNull() }

    var attempt by remember { mutableIntStateOf(0) }
    val initial = remember(book.id) { runCatching { graph.cache.availability(book) }.getOrNull() }
    var state by remember { mutableStateOf<RouteState>(if (initial.isLocal()) RouteState.Opening else RouteState.Downloading(null)) }
    // Sobrevive a recrear la actividad (rotación): el progreso guardado puede no haber llegado aún al almacén.
    // Un contenedor sin estado observable: escribir en él a cada página no recompone la ruta.
    val lastPage = rememberSaveable(book.id, saver = PageHolder.Saver) { PageHolder(-1) }

    LaunchedEffect(book.id, attempt) {
        val local = runCatching { graph.cache.availability(book) }.getOrNull()
        state = if (local.isLocal()) RouteState.Opening else RouteState.Downloading(null)
        val file: File = try {
            var lastUiUpdate = 0L
            graph.cache.ensureLocal(book) { p ->
                // El callback puede llegar muy a menudo y desde otro hilo: a la pantalla, como mucho 5 veces por segundo.
                val now = System.nanoTime()
                if (now - lastUiUpdate > 200_000_000L || (p.total != null && p.bytes >= p.total)) {
                    lastUiUpdate = now
                    state = RouteState.Downloading(p)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val offline = runCatching { !graph.transfers.isOnline.value }.getOrDefault(false)
            state = RouteState.Failed(
                headline = ReaderTexts.DOWNLOAD_FAILED,
                message = t.message ?: describe(t),
                hint = if (offline || local == Availability.CLOUD) ReaderTexts.OFFLINE_HINT else null,
            )
            return@LaunchedEffect
        }
        // Con el libro ya en disco, que la caché respete su tope sin tocar este libro ni el siguiente.
        val protect = setOfNotNull(book.id, next?.id)
        ReaderScope.launch { runCatching { graph.cache.enforceLimits(protect) } }

        state = RouteState.Opening
        var opened: ComicArchive? = null
        try {
            withContext(Dispatchers.IO) { opened = ComicArchives.open(file) }
        } catch (e: CancellationException) {
            // Se salió mientras se abría: lo abierto no se queda colgando.
            opened?.let(::closeLater)
            throw e
        } catch (t: Throwable) {
            state = RouteState.Failed(ReaderTexts.OPEN_FAILED, t.message ?: describe(t), null)
            return@LaunchedEffect
        }
        state = opened?.let { RouteState.Reading(it) }
            ?: RouteState.Failed(ReaderTexts.OPEN_FAILED, ReaderTexts.EMPTY, null)
    }

    when (val s = state) {
        is RouteState.Downloading -> {
            val cancel: () -> Unit = {
                runCatching { graph.cache.cancelDownload(book.id) }
                onClose()
            }
            BackHandler(onBack = cancel)
            DownloadingScreen(title, cover, s.progress, palette, onCancel = cancel)
        }
        RouteState.Opening -> {
            BackHandler(onBack = onClose)
            OpeningScreen(title, cover, palette)
        }
        is RouteState.Failed -> {
            BackHandler(onBack = onClose)
            RouteErrorScreen(
                headline = s.headline,
                message = s.message,
                hint = s.hint,
                cover = cover,
                palette = palette,
                onRetry = { attempt++ },
                onBack = onClose,
            )
        }
        is RouteState.Reading -> ReadingContent(
            graph = graph,
            book = book,
            next = next,
            title = title,
            archive = s.archive,
            lastPage = lastPage,
            palette = palette,
            reader = reader,
            prefetchNext = app.prefetchNext,
            scope = scope,
            onOpenBook = onOpenBook,
            onClose = onClose,
        )
    }
}

private fun Availability?.isLocal(): Boolean = this == Availability.LOCAL || this == Availability.PINNED

/** Última página vista, guardada con el estado de la actividad (no es estado de Compose: no recompone). */
internal class PageHolder(var page: Int) {
    companion object {
        val Saver: Saver<PageHolder, Int> = Saver(save = { it.page }, restore = { PageHolder(it) })
    }
}

@Composable
private fun ReadingContent(
    graph: AppGraph,
    book: Book,
    next: Book?,
    title: String,
    archive: ComicArchive,
    lastPage: PageHolder,
    palette: ReaderPalette,
    reader: ReaderSettings,
    prefetchNext: Boolean,
    scope: CoroutineScope,
    onOpenBook: (Book) -> Unit,
    onClose: () -> Unit,
) {
    DisposableEffect(archive) { onDispose { closeLater(archive) } }
    val pageCount = remember(archive) { runCatching { archive.pageCount }.getOrDefault(0) }
    val startPage = remember(archive) {
        if (lastPage.page in 0 until pageCount) lastPage.page
        else ReadingPolicy.startPage(runCatching { graph.progress.get(book.contentKey) }.getOrNull(), pageCount)
    }
    val context = LocalContext.current
    val pageCacheDir = remember(book.id) {
        runCatching { graph.cache.pageCacheDir(book) }.getOrNull() ?: File(context.cacheDir, "paginas/${book.id}")
    }

    val saver = remember(book.id) {
        ProgressSaver(
            scope = ReaderScope,
            debounceMs = 500,
            save = { page, count -> graph.progress.update(book, page, count) },
            markFinished = { count -> graph.progress.setFinished(book, true, count) },
        )
    }
    DisposableEffect(saver) { onDispose { saver.flush(close = true) } }
    val prefetched = remember(book.id) { booleanArrayOf(false) }

    // RTL efectivo: libro > carpeta > global. El contrato no lo da como flujo: se recalcula al cambiarlo desde aquí.
    var rtlVersion by remember { mutableIntStateOf(0) }
    val rtl = remember(reader.rtlGlobal, rtlVersion) {
        runCatching { graph.settings.rtlFor(book.path) }.getOrDefault(reader.rtlGlobal)
    }
    val inherited = remember(reader.rtlGlobal, rtlVersion) {
        runCatching { graph.settings.rtlFor(MangaResolution.inheritedProbePath(book.path)) }.getOrDefault(reader.rtlGlobal)
    }
    var chosen by remember(book.id) { mutableStateOf<MangaChoice?>(null) }
    val choice = chosen ?: MangaResolution.choiceFor(rtl, inherited)
    val manga = remember(choice, inherited) {
        MangaMenuModel(
            choice = choice,
            inherited = inherited,
            onChoose = { picked ->
                chosen = picked
                scope.launch {
                    withContext(NonCancellable) {
                        runCatching { graph.settings.setRtl(book.path, MangaResolution.override(picked)) }
                    }
                    rtlVersion++
                }
            },
        )
    }

    val nextName = remember(next?.id) { next?.let { displayName(graph, it) } }
    var nextCover by remember(next?.id) { mutableStateOf(next?.let { runCatching { graph.covers.coverFile(it) }.getOrNull() }) }

    ReaderCore(
        archive = archive,
        pageCacheDir = pageCacheDir,
        title = title,
        startPage = startPage,
        settings = reader,
        rtl = rtl,
        palette = palette,
        onUpdateSettings = { transform ->
            scope.launch { withContext(NonCancellable) { runCatching { graph.settings.updateReader(transform) } } }
        },
        manga = manga,
        onPosition = { position ->
            lastPage.page = position.page
            saver.onPosition(position)
            if (!prefetched[0] && prefetchNext && next != null &&
                ReadingPolicy.crossedPrefetchThreshold(position.lastPage, position.pageCount)
            ) {
                prefetched[0] = true
                ReaderTrace.log("pasado el 80 %: se pide el siguiente número por adelantado")
                runCatching { graph.transfers.prefetch(next) }
            }
        },
        endPage = {
            // La portada del siguiente se busca cuando esta página se compone (el pager la precarga en el último pliego).
            LaunchedEffect(next?.id) {
                if (next != null && nextCover == null) {
                    nextCover = runCatching { graph.covers.ensureCover(next) }.getOrNull()
                }
            }
            EndOfIssuePage(
                nextName = nextName,
                nextCover = nextCover,
                palette = palette,
                onRead = { next?.let(onOpenBook) },
                onBack = onClose,
            )
        },
        onClose = onClose,
    )
}

/** Cierra el archivo fuera del hilo principal y cuando nadie lo esté leyendo (el cargador lo usa con este cerrojo). */
private fun closeLater(archive: ComicArchive) {
    ReaderScope.launch {
        runCatching { synchronized(archive) { archive.close() } }
    }
}

/** Nombre que se enseña: el «smart name» dentro de su estante; si la biblioteca aún no lo da, el fichero sin extensión. */
internal fun displayName(graph: AppGraph, book: Book): String {
    val folderName = runCatching { graph.library.folder(book.folderId)?.name }.getOrNull()
        ?: book.folderPath.substringAfterLast('/')
    return runCatching { Naming.smartName(book.name, folderName) }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: book.name.substringBeforeLast('.', book.name)
}

/** Alto contraste: elegido, o automático con un tema sin tinte (el de Ink Paper que pone el anfitrión). */
@Composable
private fun highContrastFor(theme: ThemeMode): Boolean = when (theme) {
    ThemeMode.HIGH_CONTRAST -> true
    ThemeMode.COVER_DARK -> false
    ThemeMode.AUTO_NXTPAPER -> MaterialTheme.colorScheme.primary.isAchromatic()
}
