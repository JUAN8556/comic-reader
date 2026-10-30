package mx.jano.comics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mx.jano.comics.cache.Availability
import mx.jano.comics.ui.UiFormat
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.library.BookStatus
import mx.jano.comics.ui.library.FolderMark
import mx.jano.comics.ui.library.LibraryLogic
import mx.jano.comics.ui.theme.LocalComicsLook

/**
 * Disponibilidad de un libro: nube (no descargado), anillo con % (bajando), tablet (descargado) o chincheta (fijado).
 * Sin conexión, lo que no está en la tablet lleva la nube tachada. Forma distinta en cada caso, nunca solo color,
 * y la descripción para TalkBack.
 */
@Composable
fun AvailabilityBadge(
    availability: Availability,
    percent: Int?,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val look = LocalComicsLook.current
    val description = when (availability) {
        Availability.CLOUD -> if (online) UiStrings.AVAIL_CLOUD else UiStrings.AVAIL_CLOUD_OFFLINE
        Availability.DOWNLOADING -> UiStrings.availDownloading(percent)
        Availability.LOCAL -> UiStrings.AVAIL_LOCAL
        Availability.PINNED -> UiStrings.AVAIL_PINNED
    }
    val bg = if (look.highContrast) Color.Black else Color(0xCC000000)
    Box(
        modifier = modifier
            .size(28.dp)
            .background(bg, CircleShape)
            .then(if (look.highContrast) Modifier.border(1.dp, Color.White, CircleShape) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        when (availability) {
            Availability.DOWNLOADING -> {
                if (percent != null) {
                    CircularProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxSize().padding(2.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White,
                        trackColor = Color(0x55FFFFFF),
                    )
                    Text("$percent", color = Color.White, fontSize = 9.sp, maxLines = 1)
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.fillMaxSize().padding(3.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White,
                    )
                }
            }
            else -> Icon(
                imageVector = when (availability) {
                    Availability.CLOUD -> if (online) ComicIcons.Cloud else ComicIcons.CloudOff
                    Availability.LOCAL -> ComicIcons.Tablet
                    else -> ComicIcons.Pin
                },
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/** Marca agregada de una carpeta (icono) seguida de su resumen en texto. */
@Composable
fun FolderSummaryLine(mark: FolderMark, summary: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (mark) {
            FolderMark.ALL_READ -> Icon(Icons.Filled.Check, UiStrings.MARK_ALL_READ, Modifier.size(14.dp))
            FolderMark.IN_PROGRESS -> Icon(ComicIcons.HalfCircle, UiStrings.MARK_IN_PROGRESS, Modifier.size(13.dp))
            FolderMark.NONE -> Unit
        }
        Text(
            summary,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Estado de un libro en texto («No leído», «Pág. 10 de 36», «Leído» con la marca de visto). */
@Composable
fun BookStatusLine(status: BookStatus, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (status == BookStatus.Finished) Icon(Icons.Filled.Check, null, Modifier.size(14.dp))
        if (status is BookStatus.Reading) Icon(ComicIcons.HalfCircle, null, Modifier.size(12.dp))
        Text(
            LibraryLogic.statusText(status),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Barra fina de avance para «Pág. X de Y». */
@Composable
fun ReadingBar(fraction: Float, modifier: Modifier = Modifier) {
    val look = LocalComicsLook.current
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        trackColor = if (look.highContrast) Color(0xFF5A5A5A) else MaterialTheme.colorScheme.outlineVariant,
        drawStopIndicator = {},
    )
}

/** Porcentaje de una descarga en curso, si se sabe. */
fun downloadPercent(bytes: Long?, total: Long?): Int? = if (bytes == null) null else UiFormat.percent(bytes, total)
