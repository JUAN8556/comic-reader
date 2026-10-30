package mx.jano.comics.ui.now

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiFormat
import mx.jano.comics.ui.UiNaming
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.BookStatusLine
import mx.jano.comics.ui.components.COVER_ASPECT
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.components.CoverCard
import mx.jano.comics.ui.components.ReadingBar
import mx.jano.comics.ui.library.BookStatus
import mx.jano.comics.ui.library.LibraryLogic
import mx.jano.comics.ui.nav.Navigator
import mx.jano.comics.ui.theme.LocalComicsLook

/** «Leyendo ahora»: tarjetas grandes de lo que está en curso y, debajo, el siguiente sin leer de cada serie empezada. */
@Composable
fun NowScreen(nav: Navigator) {
    val env = LocalUiEnv.current
    val graph = env.graph
    val index by graph.library.index.collectAsStateWithLifecycle()
    val progress by graph.progress.all.collectAsStateWithLifecycle()
    val lists by produceState(NowLists(emptyList(), emptyList()), index, progress) {
        val idx = index ?: return@produceState
        value = withContext(Dispatchers.Default) {
            val byKey = idx.books.associateBy { it.contentKey }
            NowLogic.compute(
                recent = graph.progress.recent(RECENT_LIMIT),
                bookByKey = byKey::get,
                stateOf = { LibraryLogic.stateOf(progress[it.contentKey]) },
                nextAfter = { graph.library.nextAfter(it) },
            )
        }
    }
    val now = remember(progress) { System.currentTimeMillis() }
    val folderName = { b: Book -> graph.library.folder(b.folderId)?.name.orEmpty() }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ComicsTopBar(title = UiStrings.NOW_READING, onBack = nav::back)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 340.dp),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            if (lists.isEmpty) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(UiStrings.NOW_EMPTY, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 32.dp))
                }
            }
            if (lists.continueReading.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(UiStrings.NOW_CONTINUE) }
                items(lists.continueReading, key = { "c:" + it.book.id }) { item ->
                    BigCard(
                        book = item.book,
                        name = UiNaming.smartName(item.book.name, folderName(item.book)),
                        subtitle = item.book.folderPath,
                        progress = item.progress,
                        footnote = UiStrings.nowOpened(UiFormat.relativeTime(item.progress.updatedAt, now)),
                        coverWidth = 128.dp,
                        onOpen = { nav.openBook(item.book.id) },
                    )
                }
            }
            if (lists.next.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Header(UiStrings.NOW_NEXT) }
                items(lists.next, key = { "n:" + it.id }) { book ->
                    BigCard(
                        book = book,
                        name = UiNaming.smartName(book.name, folderName(book)),
                        subtitle = UiStrings.nowNextOf(folderName(book)),
                        progress = progress[book.contentKey],
                        footnote = null,
                        coverWidth = 96.dp,
                        onOpen = { nav.openBook(book.id) },
                    )
                }
            }
        }
    }
}

private const val RECENT_LIMIT = 60

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun BigCard(
    book: Book,
    name: String,
    subtitle: String,
    progress: ReadingProgress?,
    footnote: String?,
    coverWidth: Dp,
    onOpen: () -> Unit,
) {
    val look = LocalComicsLook.current
    val shape = RoundedCornerShape(10.dp)
    val status = LibraryLogic.statusOf(progress)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer, shape)
            .then(if (look.highContrast) Modifier.border(1.dp, MaterialTheme.colorScheme.outline, shape) else Modifier)
            .combinedClickable(role = Role.Button, onClick = onOpen)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.width(coverWidth).height(coverWidth / COVER_ASPECT)) {
            CoverCard(book, name, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            BookStatusLine(status)
            if (status is BookStatus.Reading) ReadingBar(status.fraction, Modifier.fillMaxWidth().height(4.dp))
            footnote?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
