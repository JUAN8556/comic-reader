package mx.jano.comics.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import mx.jano.comics.library.ReadState
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.ComicIcons
import mx.jano.comics.ui.theme.LocalComicsLook

/** Qué se sabe de un libro o carpeta al abrir su menú. */
data class MenuFacts(
    val state: ReadState?,          // libro; null = carpeta
    val stats: FolderStats?,        // carpeta; null = libro
    val pinned: Boolean,            // libro fijado / carpeta entera fijada
    val anyPinned: Boolean,
    val localCount: Int,            // descargados o fijados
    val rtl: Boolean,               // sentido efectivo ahora
    /** Lo fijado para ESTE libro o carpeta: true/false, null = hereda; [rtlKnown] false si el almacén no lo dice. */
    val rtlExplicit: Boolean? = null,
    val rtlKnown: Boolean = false,
)

/** Lo que el menú puede pedir. */
class MenuActions(
    val markRead: () -> Unit,
    val markUnread: () -> Unit,
    val pin: () -> Unit,
    val unpin: () -> Unit,
    val evict: () -> Unit,
    val setRtl: (Boolean?) -> Unit,
    val openSeries: () -> Unit,
)

/** Menú de la pulsación larga (libro o carpeta entera). Cada opción con icono y texto. */
@Composable
fun ItemMenu(expanded: Boolean, onDismiss: () -> Unit, facts: MenuFacts, actions: MenuActions) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, border = menuBorder()) {
        fun pick(action: () -> Unit): () -> Unit = {
            onDismiss()
            action()
        }
        val stats = facts.stats
        if (stats == null) {
            if (facts.state != ReadState.FINISHED) Item(UiStrings.MENU_MARK_READ, Icons.Filled.Check, pick(actions.markRead))
            if (facts.state != ReadState.UNREAD) Item(UiStrings.MENU_MARK_UNREAD, Icons.Filled.Clear, pick(actions.markUnread))
            if (facts.pinned) {
                Item(UiStrings.MENU_UNPIN, ComicIcons.Pin, pick(actions.unpin))
            } else {
                Item(UiStrings.MENU_PIN, ComicIcons.Pin, pick(actions.pin))
            }
            Item(UiStrings.MENU_DELETE_LOCAL, Icons.Filled.Delete, pick(actions.evict), enabled = facts.localCount > 0)
        } else {
            if (stats.read < stats.total) Item(UiStrings.MENU_MARK_ALL_READ, Icons.Filled.Check, pick(actions.markRead))
            if (stats.read + stats.reading > 0) Item(UiStrings.MENU_MARK_ALL_UNREAD, Icons.Filled.Clear, pick(actions.markUnread))
            if (!facts.pinned && stats.total > 0) Item(UiStrings.menuPinFolder(stats.total), ComicIcons.Pin, pick(actions.pin))
            if (facts.anyPinned) Item(UiStrings.MENU_UNPIN, ComicIcons.Pin, pick(actions.unpin))
            Item(
                if (facts.localCount > 0) UiStrings.menuDeleteLocalFolder(facts.localCount) else UiStrings.MENU_DELETE_LOCAL,
                Icons.Filled.Delete,
                pick(actions.evict),
                enabled = facts.localCount > 0,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(UiStrings.menuRtlNow(facts.rtl), style = MaterialTheme.typography.labelMedium) },
            onClick = {},
            enabled = false,
        )
        // La opción fijada lleva la marca (icono, no solo color); sin dato del almacén, ninguna.
        fun mark(chosen: Boolean): ImageVector? = if (facts.rtlKnown && chosen) Icons.Filled.Check else null
        Item(UiStrings.MENU_RTL_ON, mark(facts.rtlExplicit == true), pick { actions.setRtl(true) })
        Item(UiStrings.MENU_RTL_OFF, mark(facts.rtlExplicit == false), pick { actions.setRtl(false) })
        Item(UiStrings.MENU_RTL_INHERIT, mark(facts.rtlExplicit == null), pick { actions.setRtl(null) })
        HorizontalDivider()
        Item(UiStrings.MENU_SERIES, Icons.Filled.Info, pick(actions.openSeries))
    }
}

/** En alto contraste el menú (negro sobre negro) lleva borde blanco para que se vea dónde acaba. */
@Composable
fun menuBorder(): BorderStroke? =
    if (LocalComicsLook.current.highContrast) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null

@Composable
private fun Item(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true) {
    DropdownMenuItem(
        text = { Text(text) },
        onClick = onClick,
        enabled = enabled,
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
    )
}
