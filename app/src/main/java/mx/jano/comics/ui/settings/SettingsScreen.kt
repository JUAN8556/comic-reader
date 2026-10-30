package mx.jano.comics.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.jano.comics.cache.CacheState
import mx.jano.comics.cache.PinController
import mx.jano.comics.cache.TransferReasons
import mx.jano.comics.settings.AppSettings
import mx.jano.comics.settings.FitMode
import mx.jano.comics.settings.PageLayout
import mx.jano.comics.settings.ReaderSettings
import mx.jano.comics.settings.ThemeMode
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiEnv
import mx.jano.comics.ui.UiFormat
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.ChoiceChips
import mx.jano.comics.ui.components.ComicsTopBar
import mx.jano.comics.ui.components.SectionTitle
import mx.jano.comics.ui.nav.Navigator
import mx.jano.comics.ui.nav.Screen
import mx.jano.comics.ui.theme.NxtPaperKeys
import mx.jano.comics.ui.theme.ThemeResolver

/**
 * Ajustes: tema, lectura, descargas en curso (la cola de fijados con Pausar/Reanudar/Cancelar y lo que se baja ahora),
 * caché, carpeta raíz, copia del progreso, vaciar caché y la pantalla de diagnóstico.
 */
@Composable
fun SettingsScreen(nav: Navigator) {
    val env = LocalUiEnv.current
    val graph = env.graph
    val app by graph.settings.app.collectAsStateWithLifecycle()
    val reader by graph.settings.reader.collectAsStateWithLifecycle()
    val prefs by env.prefs.state.collectAsStateWithLifecycle()
    val ink by env.inkMode.values.collectAsStateWithLifecycle()
    val cache by graph.cache.state.collectAsStateWithLifecycle()
    val pinProgress by graph.pins.progress.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val updateApp: ((AppSettings) -> AppSettings) -> Unit = { t -> env.scope.launch { runCatching { graph.settings.updateApp(t) } } }
    val updateReader: ((ReaderSettings) -> ReaderSettings) -> Unit = { t -> env.scope.launch { runCatching { graph.settings.updateReader(t) } } }

    // Fecha de la última copia: la del fichero en el appDataFolder de Drive si se puede leer; si no, la recordada aquí.
    val lastBackup by produceState(prefs.lastBackupAt, prefs.lastBackupAt) {
        val remote = withContext(Dispatchers.IO) {
            runCatching { graph.appData.list().firstOrNull { it.name == PROGRESS_FILE }?.modifiedTime }.getOrNull()
        }
        value = listOfNotNull(UiFormat.parseIso(remote), prefs.lastBackupAt).maxOrNull()
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ComicsTopBar(title = UiStrings.SETTINGS, onBack = nav::back)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
        ) {
            Column(Modifier.widthIn(max = 840.dp)) {
                AppearanceSection(env, app, ink, prefs.inkPaperValues, updateApp)
                ReadingSection(reader, updateReader)
                DownloadsSection(env, app)
                CacheSection(app, cache, pinProgress != null, updateApp, onClear = { confirmClear = true })
                LibrarySection(app, nav, env)
                ProgressSection(lastBackup, env, onRestore = { confirmRestore = true })
                SectionTitle(UiStrings.SET_ADVANCED)
                OutlinedButton(onClick = { nav.open(Screen.Diagnostico) }) {
                    Icon(Icons.Filled.Build, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(UiStrings.SET_DIAGNOSTICO)
                }
                Help(UiStrings.SET_DIAGNOSTICO_HELP)
                Spacer(Modifier.height(12.dp))
                Help(UiStrings.setVersion(env.versionName))
                Spacer(Modifier.height(32.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(UiStrings.SET_CLEAR_CACHE_TITLE) },
            text = { Text(UiStrings.SET_CLEAR_CACHE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    env.actions.clearCache()
                }) { Text(UiStrings.SET_CLEAR_CACHE) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(UiStrings.CANCEL) } },
        )
    }
    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(UiStrings.SET_RESTORE_TITLE) },
            text = { Text(UiStrings.SET_RESTORE_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    env.actions.restore()
                }) { Text(UiStrings.SET_RESTORE) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(UiStrings.CANCEL) } },
        )
    }
}

private const val PROGRESS_FILE = "progress.json"

@Composable
private fun ColumnScope.AppearanceSection(
    env: UiEnv,
    app: AppSettings,
    ink: Map<String, String?>,
    saved: Map<String, String?>?,
    updateApp: ((AppSettings) -> AppSettings) -> Unit,
) {
    SectionTitle(UiStrings.SET_APPEARANCE)
    ChoiceChips(
        options = ThemeMode.entries,
        selected = app.theme,
        label = { themeLabel(it) },
        onSelect = { mode -> updateApp { it.copy(theme = mode) } },
    )
    Help(UiStrings.SET_THEME_HELP)
    Help(UiStrings.setThemeNow(ThemeResolver.useHighContrast(app.theme, ink, saved)))
    if (app.theme == ThemeMode.AUTO_NXTPAPER) {
        Spacer(Modifier.height(8.dp))
        Text(UiStrings.setDetected(NxtPaperKeys.describe(ink)), style = MaterialTheme.typography.bodyMedium)
        if (saved != null) {
            Text(UiStrings.setInkSaved(NxtPaperKeys.describe(saved)), style = MaterialTheme.typography.bodyMedium)
            Text(UiStrings.setInkMatches(ThemeResolver.isInkPaper(ink, saved)), style = MaterialTheme.typography.bodyMedium)
        } else {
            Help(UiStrings.SET_INK_NOT_SAVED)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                env.inkMode.refresh()
                val snapshot = env.inkMode.values.value
                env.scope.launch { runCatching { env.prefs.update { it.copy(inkPaperValues = snapshot) } } }
            }) { Text(UiStrings.SET_MARK_INK) }
            if (saved != null) {
                TextButton(onClick = {
                    env.scope.launch { runCatching { env.prefs.update { it.copy(inkPaperValues = null) } } }
                }) { Text(UiStrings.SET_FORGET_INK) }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadingSection(reader: ReaderSettings, updateReader: ((ReaderSettings) -> ReaderSettings) -> Unit) {
    SectionTitle(UiStrings.SET_READING)
    Label(UiStrings.SET_FIT)
    ChoiceChips(FitMode.entries, reader.fit, ::fitLabel, { v -> updateReader { it.copy(fit = v) } })
    Label(UiStrings.SET_LAYOUT)
    ChoiceChips(PageLayout.entries, reader.layout, ::layoutLabel, { v -> updateReader { it.copy(layout = v) } })
    Spacer(Modifier.height(8.dp))
    SwitchRow(UiStrings.SET_RTL_GLOBAL, reader.rtlGlobal) { v -> updateReader { it.copy(rtlGlobal = v) } }
    Help(UiStrings.SET_RTL_GLOBAL_HELP)
    SwitchRow(UiStrings.SET_VOLUME_KEYS, reader.volumeKeys) { v -> updateReader { it.copy(volumeKeys = v) } }
    SwitchRow(UiStrings.SET_KEEP_SCREEN, reader.keepScreenOn) { v -> updateReader { it.copy(keepScreenOn = v) } }
    SwitchRow(UiStrings.SET_TAP_ZONES, reader.tapZones) { v -> updateReader { it.copy(tapZones = v) } }
}

@Composable
private fun ColumnScope.CacheSection(
    app: AppSettings,
    cache: CacheState,
    pinning: Boolean,
    updateApp: ((AppSettings) -> AppSettings) -> Unit,
    onClear: () -> Unit,
) {
    SectionTitle(UiStrings.SET_CACHE)
    Text(
        UiStrings.setCacheState(
            used = UiFormat.bytes(cache.usedBytes),
            limit = "${app.cacheLimitGb}${UiStrings.GB_SUFFIX}",
            free = UiFormat.bytes(cache.deviceFreeBytes),
        ),
        style = MaterialTheme.typography.bodyLarge,
    )
    Help(UiStrings.setCacheEntries(cache.entries.size, cache.entries.count { it.pinned }))
    Label(UiStrings.SET_CACHE_LIMIT)
    ChoiceChips(SettingsChoices.withCurrent(SettingsChoices.CACHE_LIMITS_GB, app.cacheLimitGb), app.cacheLimitGb, { "$it${UiStrings.GB_SUFFIX}" }, { v -> updateApp { it.copy(cacheLimitGb = v) } })
    Label(UiStrings.SET_MIN_FREE)
    ChoiceChips(SettingsChoices.withCurrent(SettingsChoices.MIN_FREE_GB, app.minFreeGb), app.minFreeGb, { "$it${UiStrings.GB_SUFFIX}" }, { v -> updateApp { it.copy(minFreeGb = v) } })
    Spacer(Modifier.height(8.dp))
    SwitchRow(UiStrings.SET_PREFETCH, app.prefetchNext) { v -> updateApp { it.copy(prefetchNext = v) } }
    // Vale también para los fijados (PinController.unmeteredOnly): no depende de «Descargar el siguiente número».
    SwitchRow(UiStrings.SET_WIFI_ONLY, app.wifiOnlyPrefetch) { v -> updateApp { it.copy(wifiOnlyPrefetch = v) } }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onClear, enabled = !pinning) {
        Icon(Icons.Filled.Delete, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(UiStrings.SET_CLEAR_CACHE)
    }
    if (pinning) Help(UiStrings.SET_CLEAR_CACHE_BUSY)
}

/**
 * Lo que se baja ahora: la cola de fijados (con su estado en texto: bajando, en pausa, esperando Wi-Fi o conexión)
 * y sus botones, el último problema que tuvo y las demás transferencias (abrir un cómic, el siguiente número).
 * Antes todo esto solo se veía y se manejaba desde la notificación.
 */
@Composable
private fun ColumnScope.DownloadsSection(env: UiEnv, app: AppSettings) {
    val graph = env.graph
    val pinProgress by graph.pins.progress.collectAsStateWithLifecycle()
    val controller = graph.pins as? PinController
    val pausedFlow: StateFlow<Boolean> = remember(controller) { controller?.paused ?: MutableStateFlow(false) }
    val lastErrorFlow: StateFlow<String?> = remember(controller) { controller?.lastError ?: MutableStateFlow(null) }
    val paused by pausedFlow.collectAsStateWithLifecycle()
    val lastError by lastErrorFlow.collectAsStateWithLifecycle()
    val online by graph.transfers.isOnline.collectAsStateWithLifecycle()
    val unmetered by graph.transfers.isUnmetered.collectAsStateWithLifecycle()
    val transfers by graph.transfers.active.collectAsStateWithLifecycle()
    val status = DownloadsLogic.pinStatus(pinProgress, paused, online, unmetered, app.wifiOnlyPrefetch)

    SectionTitle(UiStrings.SET_DOWNLOADS)
    val p = pinProgress
    if (p == null && transfers.isEmpty()) Help(UiStrings.SET_DOWNLOADS_NONE)
    if (p != null) {
        Text(
            UiStrings.setPinQueue(p.done, p.total, UiFormat.bytes(p.bytesDone), UiFormat.bytes(p.bytesTotal)),
            style = MaterialTheme.typography.bodyLarge,
        )
        when (status) {
            PinQueueStatus.DOWNLOADING -> p.current?.let { Help(UiStrings.setPinCurrent(it)) }
            PinQueueStatus.PAUSED -> Help(UiStrings.SET_PIN_PAUSED)
            PinQueueStatus.WAITING_WIFI -> Help(UiStrings.SET_PIN_WAITING_WIFI)
            PinQueueStatus.WAITING_NETWORK -> Help(UiStrings.SET_PIN_WAITING_NETWORK)
            PinQueueStatus.IDLE -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) {
                Button(onClick = { graph.pins.resume() }) { Text(UiStrings.SET_PIN_RESUME) }
            } else {
                OutlinedButton(onClick = { graph.pins.pause() }) { Text(UiStrings.SET_PIN_PAUSE) }
            }
            TextButton(onClick = { graph.pins.cancel() }) { Text(UiStrings.SET_PIN_CANCEL) }
        }
        Help(UiStrings.SET_PIN_CANCEL_HELP)
    }
    lastError?.let { Help(UiStrings.setPinLastError(it)) }
    for (item in transfers) {
        if (p != null && item.reason == TransferReasons.PIN) continue // ya va arriba, con la cola
        val name = runCatching { graph.library.book(item.bookId)?.name }.getOrNull() ?: item.bookId
        val reason = TransferReasons.reasonLabel(item.reason)
        val text = if (item.bytes <= 0L && item.reason == TransferReasons.PREFETCH) {
            UiStrings.setTransferWaiting(reason, name)
        } else {
            UiStrings.setTransfer(reason, name, UiFormat.bytes(item.bytes), item.total?.let(UiFormat::bytes))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun ColumnScope.LibrarySection(app: AppSettings, nav: Navigator, env: UiEnv) {
    SectionTitle(UiStrings.SET_LIBRARY)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(UiStrings.setRoot(app.rootFolderName), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { nav.open(Screen.Onboarding(changeRoot = true)) }) { Text(UiStrings.SET_CHANGE_ROOT) }
    }
    OutlinedButton(onClick = {
        env.covers.forgetFailures()
        env.actions.rescan()
    }) {
        Icon(Icons.Filled.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(UiStrings.SET_RESCAN)
    }
}

@Composable
private fun ColumnScope.ProgressSection(lastBackup: Long?, env: UiEnv, onRestore: () -> Unit) {
    SectionTitle(UiStrings.SET_PROGRESS)
    Text(UiStrings.setLastBackup(lastBackup?.let { UiFormat.dateTime(it) }), style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = env.actions::backup) { Text(UiStrings.SET_BACKUP) }
        OutlinedButton(onClick = onRestore) { Text(UiStrings.SET_RESTORE) }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(
            if (checked) UiStrings.YES else UiStrings.NO,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
    HorizontalDivider()
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Help(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

private fun themeLabel(m: ThemeMode) = when (m) {
    ThemeMode.COVER_DARK -> UiStrings.SET_THEME_COVER
    ThemeMode.HIGH_CONTRAST -> UiStrings.SET_THEME_HC
    ThemeMode.AUTO_NXTPAPER -> UiStrings.SET_THEME_AUTO
}

private fun fitLabel(f: FitMode) = when (f) {
    FitMode.WIDTH -> UiStrings.SET_FIT_WIDTH
    FitMode.HEIGHT -> UiStrings.SET_FIT_HEIGHT
    FitMode.BOTH -> UiStrings.SET_FIT_BOTH
}

private fun layoutLabel(l: PageLayout) = when (l) {
    PageLayout.SINGLE -> UiStrings.SET_LAYOUT_SINGLE
    PageLayout.DOUBLE_AUTO -> UiStrings.SET_LAYOUT_DOUBLE
    PageLayout.VERTICAL -> UiStrings.SET_LAYOUT_VERTICAL
}
