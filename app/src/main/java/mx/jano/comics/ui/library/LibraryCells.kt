package mx.jano.comics.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.ui.UiNaming
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.AvailabilityBadge
import mx.jano.comics.ui.components.BookStatusLine
import mx.jano.comics.ui.components.COVER_ASPECT
import mx.jano.comics.ui.components.CoverCard
import mx.jano.comics.ui.components.CoverStack
import mx.jano.comics.ui.components.FolderSummaryLine
import mx.jano.comics.ui.components.ReadingBar
import mx.jano.comics.ui.now.InProgress

/** Opacidad de lo que no se puede abrir sin conexión (además lleva la nube tachada: no depende solo de esto). */
const val DIMMED_ALPHA = 0.45f

/** Una carpeta: pila de portadas, nombre, «30 cómics · 12 leídos» con su marca. Pulsación larga: menú. */
@Composable
fun FolderCell(
    tile: FolderTile,
    dimmed: Boolean,
    onOpen: () -> Unit,
    facts: () -> MenuFacts,
    actions: MenuActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var menuFacts by remember { mutableStateOf<MenuFacts?>(null) }
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dimmed) DIMMED_ALPHA else 1f)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = UiStrings.MENU_OPTIONS,
                    onLongClick = {
                        menuFacts = facts()
                        menu = true
                    },
                    onClick = onOpen,
                ),
        ) {
            CoverStack(
                books = tile.coverBooks,
                folderName = tile.folder.name,
                modifier = Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tile.folder.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FolderSummaryLine(tile.stats.mark, LibraryLogic.folderSummary(tile.stats))
        }
        menuFacts?.let { ItemMenu(expanded = menu, onDismiss = { menu = false }, facts = it, actions = actions) }
    }
}

/** Un libro: portada con su disponibilidad, nombre inteligente y «No leído / Pág. X de Y (barra) / Leído». */
@Composable
fun BookCell(
    tile: BookTile,
    availability: Availability,
    percent: Int?,
    online: Boolean,
    onOpen: () -> Unit,
    facts: () -> MenuFacts,
    actions: MenuActions,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var menuFacts by remember { mutableStateOf<MenuFacts?>(null) }
    val dimmed = !online && !LibraryLogic.isOffline(availability)
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dimmed) DIMMED_ALPHA else 1f)
                .combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = UiStrings.MENU_OPTIONS,
                    onLongClick = {
                        menuFacts = facts()
                        menu = true
                    },
                    onClick = onOpen,
                ),
        ) {
            Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT)) {
                CoverCard(tile.book, tile.displayName, Modifier.fillMaxSize())
                AvailabilityBadge(
                    availability = availability,
                    percent = percent,
                    online = online,
                    modifier = Modifier.align(Alignment.TopEnd).padding(5.dp),
                )
                val status = tile.status
                if (status is BookStatus.Reading) {
                    ReadingBar(
                        status.fraction,
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tile.displayName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BookStatusLine(tile.status)
        }
        menuFacts?.let { ItemMenu(expanded = menu, onDismiss = { menu = false }, facts = it, actions = actions) }
    }
}

/** Fila «Continuar» de la raíz: los libros en curso, el más reciente primero. */
@Composable
fun ContinueRow(items: List<InProgress>, folderNameOf: (Book) -> String, onOpen: (Book) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            UiStrings.LIB_CONTINUE,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(items, key = { it.book.id }) { item ->
                val name = UiNaming.smartName(item.book.name, folderNameOf(item.book))
                val status = LibraryLogic.statusOf(item.progress)
                Column(
                    Modifier
                        .width(112.dp)
                        .combinedClickable(role = Role.Button, onClick = { onOpen(item.book) }),
                ) {
                    Box(Modifier.fillMaxWidth().aspectRatio(COVER_ASPECT)) {
                        CoverCard(item.book, name, Modifier.fillMaxSize())
                        if (status is BookStatus.Reading) {
                            ReadingBar(status.fraction, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    BookStatusLine(status)
                }
            }
        }
    }
}
