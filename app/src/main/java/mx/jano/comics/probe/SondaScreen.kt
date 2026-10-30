package mx.jano.comics.probe

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mx.jano.comics.BuildConfig
import mx.jano.comics.reader.ReaderScreen

/**
 * Pantalla de diagnóstico del APK sonda. En vertical, una columna con el registro al final; en horizontal
 * (≥ 840 dp) los controles a la izquierda y el registro a la derecha. Ningún estado se dice solo con color:
 * cada uno lleva texto ([elegida], ERROR, «Trabajando: …», «sí/no»).
 */
@Composable
fun SondaScreen(vm: SondaViewModel = viewModel()) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val lines by vm.log.lines.collectAsStateWithLifecycle()

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> vm.onConsentResult(result.resultCode, result.data) }

    val consent = state.consent
    LaunchedEffect(consent) {
        if (consent != null) {
            try {
                consentLauncher.launch(IntentSenderRequest.Builder(consent).build())
                vm.onConsentLaunched()
            } catch (t: Throwable) {
                vm.onConsentLaunchFailed(t)
            }
        }
    }

    val opened = state.opened
    if (state.reading && opened != null) {
        ReaderScreen(
            archive = opened.archive,
            pageCacheDir = vm.pageCacheDirFor(opened),
            startPage = 0,
            rtl = state.rtl,
            onClose = vm::stopReading,
        )
        return
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(insets)
                .consumeWindowInsets(insets),
        ) {
            if (maxWidth >= 840.dp) {
                Row(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Controls(state, vm)
                    }
                    LogPanel(lines, Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    Controls(state, vm)
                    Spacer(Modifier.height(16.dp))
                    LogPanel(lines, Modifier.fillMaxWidth().height(440.dp))
                }
            }
        }
    }
}

@Composable
private fun Controls(state: SondaUiState, vm: SondaViewModel) {
    val idle = state.busy == null
    Text(SondaTexts.TITLE, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
    Text(
        "${SondaTexts.SUBTITLE} · ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        state.busy?.let { SondaTexts.BUSY_PREFIX + it } ?: SondaTexts.IDLE,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (idle) FontWeight.Normal else FontWeight.Bold,
    )

    Section(SondaTexts.SECTION_DEVICE) {
        Text(SondaTexts.DEVICE_HELP, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::probeDevice, enabled = idle) { Text(SondaTexts.DEVICE_REPEAT) }
            OutlinedButton(onClick = vm::readInkMode, enabled = idle) { Text(SondaTexts.DEVICE_INK_NOW) }
            OutlinedButton(onClick = vm::toggleWatch) {
                Text(if (state.watching) SondaTexts.WATCH_ON else SondaTexts.WATCH_OFF)
            }
        }
        Text(SondaTexts.DEVICE_INK_HELP, style = MaterialTheme.typography.bodySmall)
    }

    Section(SondaTexts.SECTION_AUTH) {
        Text("Estado: ${state.authStatus}", style = MaterialTheme.typography.bodyMedium)
        if (state.grantedScopes.isNotEmpty()) {
            Text("Scopes concedidos: ${state.grantedScopes.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        state.firstTokenInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect, enabled = idle) { Text(SondaTexts.AUTH_CONNECT) }
            OutlinedButton(onClick = vm::silentToken, enabled = idle) { Text(SondaTexts.AUTH_SILENT) }
            OutlinedButton(onClick = vm::forceToken, enabled = idle) { Text(SondaTexts.AUTH_FORCE) }
        }
        Text(SondaTexts.AUTH_HELP, style = MaterialTheme.typography.bodySmall)
    }

    Section(SondaTexts.SECTION_LIST) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::listComics, enabled = idle) { Text(SondaTexts.LIST_RUN) }
            OutlinedButton(onClick = vm::sniffCandidates, enabled = idle && state.candidates.isNotEmpty()) {
                Text(SondaTexts.LIST_HEADS)
            }
        }
        val sample = state.sample
        Text(
            if (sample == null) SondaTexts.LIST_EMPTY
            else "Muestra: ${sample.name} · ${sample.size?.let(ProbeFormat::mb) ?: "?"}" +
                (state.heads[sample.id]?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.candidates.isNotEmpty()) Text(SondaTexts.LIST_HEADS_HELP, style = MaterialTheme.typography.bodySmall)
        if (state.candidates.size > 1) {
            Text(SondaTexts.LIST_PICK_HELP, style = MaterialTheme.typography.bodySmall)
            Column {
                state.candidates.forEach { f ->
                    val chosen = f.id == sample?.id
                    Text(
                        text = (if (chosen) SondaTexts.LIST_CHOSEN else "") +
                            (state.heads[f.id]?.let { "[$it] " } ?: "") +
                            "${f.name} · ${f.size?.let(ProbeFormat::mb) ?: "?"}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = idle) { vm.selectSample(f) }
                            .padding(vertical = 6.dp),
                    )
                }
            }
        }
    }

    Section(SondaTexts.SECTION_DOWNLOAD) {
        val sample = state.sample
        val total = state.progress?.total ?: sample?.size
        val bytes = state.progress?.bytes ?: state.localBytes
        if (sample != null) {
            val ofTotal = total?.let { " de ${ProbeFormat.mb(it)}" } ?: ""
            val speed = if (state.downloading) " · ${state.progress?.speed ?: ""}" else ""
            val tag = when {
                state.downloading -> "descargando"
                total != null && bytes >= total -> "completo"
                bytes > 0 -> "parcial"
                else -> "sin descargar"
            }
            Text("En disco: ${ProbeFormat.mb(bytes)}$ofTotal$speed ($tag)", style = MaterialTheme.typography.bodyMedium)
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (bytes.toFloat() / total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::download, enabled = idle && sample != null) { Text(SondaTexts.DOWNLOAD_RUN) }
            OutlinedButton(onClick = vm::cut, enabled = state.downloading) { Text(SondaTexts.DOWNLOAD_CUT) }
            TextButton(onClick = vm::deleteLocal, enabled = idle && state.localBytes > 0) { Text(SondaTexts.DOWNLOAD_DELETE) }
        }
        Text(SondaTexts.DOWNLOAD_HELP, style = MaterialTheme.typography.bodySmall)
    }

    Section(SondaTexts.SECTION_OPEN) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::openArchive, enabled = idle && state.localBytes > 0) { Text(SondaTexts.OPEN_RUN) }
            OutlinedButton(onClick = vm::openSyntheticSolid, enabled = idle) { Text(SondaTexts.OPEN_SYNTHETIC) }
        }
        state.opened?.let { Text("Abierto: ${it.fileName} · ${it.summary}", style = MaterialTheme.typography.bodyMedium) }
        Text(SondaTexts.OPEN_SYNTHETIC_HELP, style = MaterialTheme.typography.bodySmall)
    }

    Section(SondaTexts.SECTION_READ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = state.rtl, onCheckedChange = vm::setRtl)
            Spacer(Modifier.width(12.dp))
            Text(
                "${SondaTexts.READ_RTL}: ${if (state.rtl) SondaTexts.READ_RTL_ON else SondaTexts.READ_RTL_OFF}",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(onClick = vm::startReading, enabled = idle && state.opened != null) { Text(SondaTexts.READ_RUN) }
        if (state.opened == null) Text(SondaTexts.READ_NEEDS_OPEN, style = MaterialTheme.typography.bodySmall)
    }

    Section(SondaTexts.SECTION_LOG) {
        Button(onClick = vm::copyLog) { Text(SondaTexts.LOG_COPY) }
        val path = vm.logPath
        SelectionContainer {
            Column {
                Text(SondaTexts.LOG_PATH + (path ?: SondaTexts.LOG_NO_FILE), style = MaterialTheme.typography.bodySmall)
                if (path != null) {
                    Text(
                        SondaTexts.LOG_PULL + path,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
private fun LogPanel(lines: List<String>, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(modifier) {
        Text(SondaTexts.LOG_TITLE, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.padding(8.dp)) {
                    itemsIndexed(lines) { _, line ->
                        val isError = "ERROR" in line
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            fontWeight = if (isError) FontWeight.Bold else FontWeight.Normal,
                            color = if (isError) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}
