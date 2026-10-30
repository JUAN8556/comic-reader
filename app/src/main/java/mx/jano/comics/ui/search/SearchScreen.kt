package mx.jano.comics.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiNaming
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.BookStatusLine
import mx.jano.comics.ui.components.COVER_ASPECT
import mx.jano.comics.ui.components.ComicIcons
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.components.CoverCard
import mx.jano.comics.ui.library.BookStatus
import mx.jano.comics.ui.library.LibraryLogic
import mx.jano.comics.ui.nav.Navigator

/** Búsqueda en toda la biblioteca por nombre (sin acentos ni mayúsculas), con los cómics agrupados por carpeta. */
@Composable
fun SearchScreen(nav: Navigator) {
    val env = LocalUiEnv.current
    val graph = env.graph
    val index by graph.library.index.collectAsStateWithLifecycle()
    val progress by graph.progress.all.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val corpus by produceState<SearchCorpus?>(null, index) {
        val idx = index ?: return@produceState
        value = withContext(Dispatchers.Default) { SearchCorpus(idx) }
    }
    val results by produceState(SearchResults.EMPTY, corpus, query) {
        val c = corpus ?: return@produceState
        delay(120)
        value = withContext(Dispatchers.Default) { SearchLogic.search(c, query, UiNaming.natural) }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        ComicsTopBar(
            title = null,
            onBack = nav::back,
            titleContent = {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text(UiStrings.SEARCH_HINT, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, UiStrings.SEARCH_CLEAR) }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f).padding(end = 12.dp, top = 4.dp, bottom = 4.dp).focusRequester(focus),
                )
            },
        )
        LazyColumn(
            state = rememberLazyListState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 24.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            when {
                index == null -> item { Hint(UiStrings.SEARCH_NO_INDEX) }
                query.isBlank() -> item { Hint(UiStrings.SEARCH_EMPTY_QUERY) }
                results.query == query && results.isEmpty -> item { Hint(UiStrings.SEARCH_NO_RESULTS) }
            }
            if (results.folders.isNotEmpty()) {
                item(key = "h:folders") { GroupHeader("${UiStrings.SEARCH_FOLDERS} (${results.folders.size})") }
                items(results.folders, key = { "f:" + it.id }) { folder -> FolderRow(folder) { nav.openFolder(folder.id) } }
            }
            for (group in results.groups) {
                item(key = "h:" + group.folderId) {
                    GroupHeader(group.folderPath.ifEmpty { group.folderName }, onClick = { nav.openFolder(group.folderId) })
                }
                items(group.books, key = { "b:" + it.id }) { book ->
                    BookRow(
                        book = book,
                        name = UiNaming.smartName(book.name, group.folderName),
                        status = LibraryLogic.statusOf(progress[book.contentKey]),
                        onClick = { nav.openBook(book.id) },
                    )
                }
            }
            if (results.truncated) item(key = "more") { Hint(UiStrings.searchTruncated(results.shownBooks, results.bookCount)) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun GroupHeader(text: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = (if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 6.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun FolderRow(folder: Folder, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ComicIcons.Stack, contentDescription = null, modifier = Modifier.size(24.dp))
        Column(Modifier.padding(start = 14.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                folder.path,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun BookRow(book: Book, name: String, status: BookStatus, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(40.dp).height(40.dp / COVER_ASPECT)) { CoverCard(book, "", Modifier.fillMaxSize(), elevated = false) }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            BookStatusLine(status)
        }
    }
}
