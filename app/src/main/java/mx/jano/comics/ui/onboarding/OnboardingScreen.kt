package mx.jano.comics.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.UiStrings
import mx.jano.comics.ui.components.ComicIcons
import mx.jano.comics.ui.components.ComicsTopBar

/**
 * Primer arranque: «Conectar Google Drive» y elegir la carpeta raíz. [onDone] recibe el id de la raíz elegida.
 * [onCancel] solo en el cambio de carpeta desde Ajustes.
 */
@Composable
fun OnboardingScreen(changeRoot: Boolean, onDone: (String) -> Unit, onCancel: (() -> Unit)?) {
    val graph = LocalUiEnv.current.graph
    val vm: OnboardingViewModel = viewModel(key = if (changeRoot) "onb-change" else "onb") {
        OnboardingViewModel(graph, changeRoot)
    }
    remember(vm) {
        vm.onShown()
        true
    }
    val step by vm.step.collectAsStateWithLifecycle()
    val consent by vm.consent.collectAsStateWithLifecycle()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.onConsentResult(result.resultCode, result.data)
    }
    LaunchedEffect(consent) {
        val pi = consent ?: return@LaunchedEffect
        try {
            launcher.launch(IntentSenderRequest.Builder(pi).build())
            vm.onConsentLaunched()
        } catch (t: Throwable) {
            vm.onConsentLaunchFailed(t)
        }
    }
    LaunchedEffect(step) {
        (step as? OnbStep.Done)?.let { onDone(it.rootId) }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (onCancel != null) ComicsTopBar(title = UiStrings.ONB_PICK_TITLE, onBack = onCancel)
        Box(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = 640.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (onCancel == null) {
                    Spacer(Modifier.height(32.dp))
                    Text(UiStrings.APP_TITLE, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                }
                when (val s = step) {
                    OnbStep.Checking -> Working(UiStrings.ONB_CHECKING)
                    OnbStep.WaitingConsent -> Working(UiStrings.ONB_WAITING)
                    OnbStep.LookingForRoot -> Working(UiStrings.ONB_LOOKING)
                    OnbStep.Saving, is OnbStep.Done -> Working(UiStrings.ONB_SAVING)
                    is OnbStep.NeedsConnect -> Connect(s.error, vm::connect)
                    is OnbStep.Proposal -> Proposal(s.folder, onUse = { vm.use(s.folder) }, onOther = vm::browseFromRoot)
                    is OnbStep.Browsing -> Browser(s, vm)
                    is OnbStep.Failed -> {
                        ErrorLine(s.message)
                        Button(onClick = vm::retry) { Text(UiStrings.RETRY) }
                    }
                }
                if (onCancel != null && step !is OnbStep.Browsing) {
                    TextButton(onClick = onCancel) { Text(UiStrings.ONB_CANCEL_CHANGE) }
                }
            }
        }
    }
}

@Composable
private fun Working(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ErrorLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Filled.Warning, contentDescription = null)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ColumnScope.Connect(error: String?, onConnect: () -> Unit) {
    Text(UiStrings.ONB_TITLE, style = MaterialTheme.typography.headlineSmall)
    Text(UiStrings.ONB_BODY, style = MaterialTheme.typography.bodyLarge)
    error?.let { ErrorLine(it) }
    Button(onClick = onConnect) { Text(UiStrings.ONB_CONNECT) }
}

@Composable
private fun ColumnScope.Proposal(folder: DriveFile, onUse: () -> Unit, onOther: () -> Unit) {
    Text(UiStrings.ONB_FOUND_TITLE, style = MaterialTheme.typography.headlineSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(ComicIcons.Stack, contentDescription = null, modifier = Modifier.size(32.dp))
        Text(UiStrings.onbFoundBody(folder.name), style = MaterialTheme.typography.bodyLarge)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onUse) { Text(UiStrings.ONB_USE_THIS) }
        OutlinedButton(onClick = onOther) { Text(UiStrings.ONB_CHOOSE_OTHER) }
    }
}

@Composable
private fun ColumnScope.Browser(s: OnbStep.Browsing, vm: OnboardingViewModel) {
    Text(UiStrings.ONB_PICK_TITLE, style = MaterialTheme.typography.headlineSmall)
    Text(UiStrings.ONB_PICK_HELP, style = MaterialTheme.typography.bodyMedium)
    Text(
        (listOf(UiStrings.MY_DRIVE) + s.trail.map { it.name }).joinToString(" › "),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val current = s.trail.lastOrNull()
        Button(onClick = { current?.let(vm::use) }, enabled = current != null) {
            Text(current?.let { UiStrings.onbUse(it.name) } ?: UiStrings.ONB_USE_THIS, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (s.trail.isNotEmpty()) {
            OutlinedButton(onClick = vm::up) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null)
                Text(UiStrings.ONB_UP)
            }
        }
    }
    s.error?.let {
        ErrorLine(it)
        TextButton(onClick = vm::retryBrowse) { Text(UiStrings.RETRY) }
    }
    val folders = s.folders
    when {
        folders == null -> Working(UiStrings.LOADING)
        folders.isEmpty() && s.error == null -> Text(UiStrings.ONB_NO_SUBFOLDERS, style = MaterialTheme.typography.bodyMedium)
        else -> LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false)) {
            items(folders, key = { it.id }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.enter(f) }.padding(vertical = 14.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(ComicIcons.Stack, contentDescription = null, modifier = Modifier.size(24.dp))
                    Text(
                        f.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f).padding(horizontal = 14.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
                HorizontalDivider()
            }
        }
    }
}
