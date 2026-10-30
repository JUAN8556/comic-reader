package mx.jano.comics.ui.series

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mx.jano.comics.metadata.SeriesMeta
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiFormat
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.Banner
import mx.jano.comics.ui.components.COVER_ASPECT
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.components.CoverCard
import mx.jano.comics.ui.components.SectionTitle

private data class SeriesData(val meta: SeriesMeta?, val missing: List<Int>)

/** Ficha de la serie de una carpeta (nivel 2, de .indice/series.jsonl). Sin ficha: «Sin ficha todavía». */
@Composable
fun SeriesScreen(folderId: String, onBack: () -> Unit) {
    val env = LocalUiEnv.current
    val graph = env.graph
    val index by graph.library.index.collectAsStateWithLifecycle()
    val folder = graph.library.folder(folderId)
    val folderName = folder?.name.orEmpty()
    val data by produceState<SeriesData?>(null, index, folderId) {
        val f = folder ?: run {
            value = SeriesData(null, emptyList())
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            val meta = runCatching { graph.metadata.seriesFor(f.path) }.getOrNull()
            val missing = if (meta == null) {
                emptyList()
            } else {
                runCatching { graph.metadata.missingNumbers(f.path, graph.library.booksUnder(f.id)) }.getOrDefault(emptyList())
            }
            SeriesData(meta, missing)
        }
    }
    val firstBook = folder?.let { graph.library.booksUnder(it.id).firstOrNull() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ComicsTopBar(title = UiStrings.SERIES_TITLE, onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            val d = data
            val meta = d?.meta
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Box(Modifier.width(140.dp).height(140.dp / COVER_ASPECT)) {
                    CoverCard(firstBook, folderName, Modifier.fillMaxSize())
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(SeriesLogic.title(meta, folderName), style = MaterialTheme.typography.headlineSmall)
                    folder?.path?.takeIf { it.isNotEmpty() }?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    folder?.let {
                        Text(UiFormat.comics(it.totalBooks), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                d == null -> Text(UiStrings.LOADING, style = MaterialTheme.typography.bodyMedium)
                meta == null -> {
                    Banner(UiStrings.SERIES_NONE, icon = Icons.Filled.Info)
                    Spacer(Modifier.height(8.dp))
                    Text(UiStrings.SERIES_NONE_HELP, style = MaterialTheme.typography.bodyMedium)
                }
                else -> SeriesDetails(meta, d.missing)
            }
        }
    }
}

@Composable
private fun SeriesDetails(meta: SeriesMeta, missing: List<Int>) {
    Column(Modifier.widthIn(max = 900.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (SeriesLogic.isLowConfidence(meta)) Banner(UiStrings.SERIES_LOW_CONFIDENCE, icon = Icons.Filled.Warning)
        if (meta.numeros_totales != null) {
            if (missing.isEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(UiStrings.SERIES_COMPLETE, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Banner(UiStrings.seriesMissing(UiFormat.numberRanges(missing)), icon = Icons.Filled.Warning)
            }
        }
        for (row in SeriesLogic.rows(meta)) {
            Row {
                Text(
                    row.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(170.dp),
                )
                Text(row.value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
        meta.sinopsis?.takeIf { it.isNotBlank() }?.let {
            SectionTitle(UiStrings.SERIES_SYNOPSIS)
            Text(it, style = MaterialTheme.typography.bodyLarge)
        }
        if (meta.fuentes.isNotEmpty()) {
            SectionTitle(UiStrings.SERIES_SOURCES)
            val linkStyle = TextLinkStyles(
                style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline),
            )
            for (source in meta.fuentes) {
                if (SeriesLogic.isLink(source)) {
                    Text(
                        buildAnnotatedString { withLink(LinkAnnotation.Url(source, linkStyle)) { append(source) } },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                } else {
                    Text(source, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        }
        meta.notas?.takeIf { it.isNotBlank() }?.let {
            SectionTitle(UiStrings.SERIES_NOTES)
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(24.dp))
    }
}
