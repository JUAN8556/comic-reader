package mx.jano.comics.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mx.jano.comics.AppGraph
import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.library.ScanState
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiEnv
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.Banner
import mx.jano.comics.ui.components.BarAction
import mx.jano.comics.ui.components.ComicIcons
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.components.downloadPercent
import mx.jano.comics.ui.components.rememberCoverFile
import mx.jano.comics.ui.nav.Navigator
import mx.jano.comics.ui.nav.Screen
import mx.jano.comics.ui.theme.CoverColors
import mx.jano.comics.ui.theme.LocalComicsLook

/**
 * La biblioteca: espejo de las carpetas de Drive. Rejilla de carpetas (pilas de portadas) y libros, migas de pan,
 * filtro, tirar para volver a escanear, menú de pulsación larga y fondo con la portada difuminada de la carpeta.
 * Sin conexión sigue navegable con el índice local; lo que no está en la tablet se atenúa y lleva la nube tachada.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(folderId: String, nav: Navigator) {
    val env = LocalUiEnv.current
    val graph = env.graph
    val index by graph.library.index.collectAsStateWithLifecycle()
    val scan by graph.library.scanState.collectAsStateWithLifecycle()
    val progress by graph.progress.all.collectAsStateWithLifecycle()
    val pins by graph.pins.pins.collectAsStateWithLifecycle()
    val cacheState by graph.cache.state.collectAsStateWithLifecycle()
    val downloads by graph.cache.downloads.collectAsStateWithLifecycle()
    val online by graph.transfers.isOnline.collectAsStateWithLifecycle()
    val appSettings by graph.settings.app.collectAsStateWithLifecycle()
    val prefs by env.prefs.state.collectAsStateWithLifecycle()
    val filter = prefs.libraryFilter
    val rootId = appSettings.rootFolderId ?: index?.rootId
    val isRoot = folderId == rootId

    val bookByKey = remember(index) { index?.books?.associateBy { it.contentKey }.orEmpty() }
    val content by produceState(env.folderContents[folderId], index, folderId, isRoot, progress, filter, pins, cacheState) {
        val idx = index
        value = if (idx == null) {
            null
        } else {
            val built = withContext(Dispatchers.Default) {
                LibraryContentBuilder.build(
                    library = graph.library,
                    folderId = folderId,
                    isRoot = isRoot,
                    progress = progress,
                    filter = filter,
                    availabilityOf = { graph.cache.availability(it) },
                    hasCover = { b -> runCatching { graph.covers.coverFile(b) }.getOrNull() != null },
                    recent = { graph.progress.recent(CONTINUE_LIMIT) },
                    bookByKey = bookByKey::get,
                )
            }
            env.folderContents[folderId] = built
            built
        }
    }

    var offlineBook by remember { mutableStateOf<Book?>(null) }
    val openBook: (Book) -> Unit = { book ->
        if (!online && !LibraryLogic.isOffline(graph.cache.availability(book))) offlineBook = book else nav.openBook(book.id)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Backdrop(content?.backdrop)
        Column(Modifier.fillMaxSize()) {
            val rootName = appSettings.rootFolderName ?: index?.rootName ?: UiStrings.APP_TITLE
            LibraryTopBar(
                crumbs = content?.crumbs.orEmpty(),
                rootName = rootName,
                isRoot = isRoot,
                filter = filter,
                nav = nav,
                onFilter = env.actions::setFilter,
            )
            PullToRefreshBox(
                isRefreshing = scan is ScanState.Scanning,
                onRefresh = {
                    env.covers.forgetFailures()
                    env.actions.rescan()
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                val c = content
                when {
                    index == null -> EmptyState(
                        text = UiStrings.LIB_EMPTY_INDEX,
                        scan = scan,
                        action = UiStrings.LIB_SCAN_NOW,
                        onAction = env.actions::rescan,
                    )
                    c == null -> Unit
                    c.folder == null && !isRoot -> EmptyState(
                        text = UiStrings.LIB_FOLDER_GONE,
                        scan = scan,
                        action = UiStrings.LIB_GO_ROOT,
                        onAction = { rootId?.let { nav.resetTo(Screen.Library(it)) } },
                    )
                    else -> LibraryGrid(
                        content = c,
                        env = env,
                        scan = scan,
                        online = online,
                        filter = filter,
                        downloads = downloads.mapValues { downloadPercent(it.value.bytes, it.value.total) },
                        pins = pins,
                        cacheKey = cacheState,
                        nav = nav,
                        openBook = openBook,
                    )
                }
            }
        }
    }

    offlineBook?.let { book ->
        AlertDialog(
            onDismissRequest = { offlineBook = null },
            icon = { Icon(ComicIcons.CloudOff, contentDescription = null) },
            title = { Text(UiStrings.LIB_OFFLINE_DIALOG_TITLE) },
            text = { Text(UiStrings.libOfflineDialogBody(book.name)) },
            confirmButton = { TextButton(onClick = { offlineBook = null }) { Text(UiStrings.UNDERSTOOD) } },
        )
    }
}

private const val CONTINUE_LIMIT = 16

@Composable
private fun LibraryGrid(
    content: FolderContent,
    env: UiEnv,
    scan: ScanState,
    online: Boolean,
    filter: LibraryFilter,
    downloads: Map<String, Int?>,
    pins: Set<String>,
    cacheKey: Any,
    nav: Navigator,
    openBook: (Book) -> Unit,
) {
    val graph = env.graph
    val sizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
    val columns = LibraryLogic.gridColumns(
        widthDp = LocalConfiguration.current.screenWidthDp,
        atLeastMedium = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND),
        atLeastExpanded = sizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND),
    )
    val gap = if (columns >= 7) 14.dp else 16.dp
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gridState = rememberLazyGridState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        banners(scan, online, env)
        val showContinue = content.continueReading.isNotEmpty() &&
            (filter == LibraryFilter.ALL || filter == LibraryFilter.READING)
        if (showContinue) {
            fullSpan("continue") {
                ContinueRow(
                    items = content.continueReading,
                    folderNameOf = { b -> graph.library.folder(b.folderId)?.name.orEmpty() },
                    onOpen = openBook,
                )
            }
        }
        items(content.folders, key = { "f:" + it.folder.id }) { tile ->
            FolderCell(
                tile = tile,
                dimmed = !online && tile.stats.offline == 0,
                onOpen = { nav.openFolder(tile.folder.id) },
                facts = { folderFacts(graph, tile, pins) },
                actions = folderActions(env, tile.folder, nav),
            )
        }
        items(content.books, key = { "b:" + it.book.id }) { tile ->
            val book = tile.book
            val percent = downloads[book.id]
            val downloading = downloads.containsKey(book.id)
            val availability = remember(book.id, cacheKey, pins, downloading) {
                runCatching { graph.cache.availability(book) }.getOrDefault(Availability.CLOUD)
            }
            BookCell(
                tile = tile,
                availability = if (downloading && availability == Availability.CLOUD) Availability.DOWNLOADING else availability,
                percent = percent,
                online = online,
                onOpen = { openBook(book) },
                facts = { bookFacts(graph, book, pins) },
                actions = bookActions(env, book, nav),
            )
        }
        if (content.isEmpty) {
            fullSpan("empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val filtered = content.totalChildren > 0 && filter != LibraryFilter.ALL
                    Text(
                        if (filtered) UiStrings.LIB_EMPTY_FILTER else UiStrings.LIB_EMPTY_FOLDER,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (filtered) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { env.actions.setFilter(LibraryFilter.ALL) }) { Text(UiStrings.LIB_SHOW_ALL) }
                    } else {
                        Spacer(Modifier.height(6.dp))
                        Text(UiStrings.LIB_PULL_HINT, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.fullSpan(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Avisos sobre la rejilla: escaneo en curso / hecho / con error, y sin conexión. */
private fun LazyGridScope.banners(scan: ScanState, online: Boolean, env: UiEnv) {
    fullSpan("banners") {
        var sawScanning by remember { mutableStateOf(false) }
        var showDone by remember { mutableStateOf(false) }
        LaunchedEffect(scan) {
            when (scan) {
                is ScanState.Scanning -> sawScanning = true
                is ScanState.Done -> if (sawScanning) {
                    showDone = true
                    delay(5_000)
                    showDone = false
                    sawScanning = false
                }
                else -> Unit
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (scan) {
                is ScanState.Scanning -> Banner(UiStrings.libScanning(scan.folders, scan.books), progress = true)
                is ScanState.Error -> Banner(
                    UiStrings.libScanError(scan.message),
                    icon = Icons.Filled.Warning,
                    actionLabel = UiStrings.RETRY,
                    onAction = env.actions::rescan,
                )
                is ScanState.Done -> if (showDone) {
                    Banner(UiStrings.libScanDone(scan.folders, scan.books, scan.ms), icon = Icons.Filled.Check)
                }
                ScanState.Idle -> Unit
            }
            if (!online) Banner(UiStrings.LIB_OFFLINE_BANNER, icon = ComicIcons.CloudOff)
        }
    }
}

private fun bookFacts(graph: AppGraph, book: Book, pins: Set<String>): MenuFacts {
    val availability = runCatching { graph.cache.availability(book) }.getOrDefault(Availability.CLOUD)
    return MenuFacts(
        state = LibraryLogic.stateOf(graph.progress.all.value[book.contentKey]),
        stats = null,
        pinned = book.id in pins,
        anyPinned = book.id in pins,
        localCount = if (availability == Availability.CLOUD) 0 else 1,
        rtl = runCatching { graph.settings.rtlFor(book.path) }.getOrDefault(false),
    )
}

private fun folderFacts(graph: AppGraph, tile: FolderTile, pins: Set<String>): MenuFacts {
    val under = graph.library.booksUnder(tile.folder.id)
    return MenuFacts(
        state = null,
        stats = tile.stats,
        pinned = under.isNotEmpty() && under.all { it.id in pins },
        anyPinned = under.any { it.id in pins },
        localCount = under.count { runCatching { graph.cache.availability(it) }.getOrNull().let { a -> a != null && a != Availability.CLOUD } },
        rtl = runCatching { graph.settings.rtlFor(tile.folder.path) }.getOrDefault(false),
    )
}

private fun bookActions(env: UiEnv, book: Book, nav: Navigator) = MenuActions(
    markRead = { env.actions.markFinished(listOf(book), true) },
    markUnread = { env.actions.markFinished(listOf(book), false) },
    pin = { env.actions.pin(listOf(book)) },
    unpin = { env.actions.unpin(listOf(book)) },
    evict = { env.actions.evict(listOf(book)) },
    setRtl = { env.actions.setRtl(book.path, it) },
    openSeries = { nav.open(Screen.Series(book.folderId)) },
)

private fun folderActions(env: UiEnv, folder: Folder, nav: Navigator): MenuActions {
    val books = { env.graph.library.booksUnder(folder.id) }
    return MenuActions(
        markRead = { env.actions.markFinished(books(), true) },
        markUnread = { env.actions.markFinished(books(), false) },
        pin = { env.actions.pin(books()) },
        unpin = { env.actions.unpin(books().filter { env.graph.pins.isPinned(it) }) },
        evict = { env.actions.evict(books()) },
        setRtl = { env.actions.setRtl(folder.path, it) },
        openSeries = { nav.open(Screen.Series(folder.id)) },
    )
}

/** Portada de la carpeta actual difuminada bajo un velo oscuro. En alto contraste, fondo negro liso. */
@Composable
private fun Backdrop(book: Book?) {
    val look = LocalComicsLook.current
    if (!look.decorations || book == null) return
    val file = rememberCoverFile(book)
    if (file != null) {
        AsyncImage(
            model = file,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(40.dp),
        )
    }
    Box(Modifier.fillMaxSize().background(CoverColors.Scrim))
}

@Composable
private fun LibraryTopBar(
    crumbs: List<Folder>,
    rootName: String,
    isRoot: Boolean,
    filter: LibraryFilter,
    nav: Navigator,
    onFilter: (LibraryFilter) -> Unit,
) {
    val wide = currentWindowAdaptiveInfoV2().windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    ComicsTopBar(
        title = null,
        onBack = if (isRoot) null else nav::back,
        titleContent = {
            Breadcrumbs(crumbs, rootName, onCrumb = nav::goToAncestor, modifier = Modifier.weight(1f))
        },
        actions = {
            BarAction(Icons.Filled.PlayArrow, UiStrings.NOW_READING, { nav.open(Screen.Now) }, showLabel = wide)
            BarAction(Icons.Filled.Search, UiStrings.SEARCH, { nav.open(Screen.Search) }, showLabel = false)
            FilterButton(filter, onFilter, showLabel = wide)
            BarAction(Icons.Filled.Settings, UiStrings.SETTINGS, { nav.open(Screen.Settings) }, showLabel = false)
        },
    )
}

/** Migas de pan: «Cómics › Americano › Marvel»; las anteriores a la actual se pueden tocar. */
@Composable
private fun Breadcrumbs(crumbs: List<Folder>, rootName: String, onCrumb: (String) -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    val max = scroll.maxValue
    LaunchedEffect(crumbs.size, max) { scroll.scrollTo(max) }
    Row(modifier = modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        if (crumbs.isEmpty()) {
            Text(rootName, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            return@Row
        }
        crumbs.forEachIndexed { i, folder ->
            val last = i == crumbs.lastIndex
            val name = if (folder.parentId == null) rootName else folder.name
            if (i > 0) {
                Text(" › ", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                name,
                style = if (last) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = if (last) Modifier else Modifier.clickable { onCrumb(folder.id) }.padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun RowScope.FilterButton(filter: LibraryFilter, onFilter: (LibraryFilter) -> Unit, showLabel: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        val label = if (filter == LibraryFilter.ALL) UiStrings.FILTER else LibraryLogic.filterLabel(filter)
        BarAction(ComicIcons.Filter, label, { open = true }, showLabel = showLabel || filter != LibraryFilter.ALL)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (f in LibraryFilter.entries) {
                DropdownMenuItem(
                    text = { Text(LibraryLogic.filterLabel(f)) },
                    onClick = {
                        open = false
                        onFilter(f)
                    },
                    leadingIcon = if (f == filter) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        { Spacer(Modifier.size(24.dp)) }
                    },
                )
            }
        }
    }
}

/** Estado vacío desplazable (para que «tirar para refrescar» funcione también aquí). */
@Composable
private fun EmptyState(text: String, scan: ScanState, action: String?, onAction: (() -> Unit)?) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        when (scan) {
            is ScanState.Scanning -> Banner(UiStrings.libScanning(scan.folders, scan.books), progress = true)
            is ScanState.Error -> Banner(UiStrings.libScanError(scan.message), icon = Icons.Filled.Warning)
            else -> Unit
        }
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (action != null && onAction != null && scan !is ScanState.Scanning) {
            Button(onClick = onAction) { Text(action) }
        }
    }
}
