package mx.jano.comics.ui.onboarding

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import mx.jano.comics.AppGraph
import mx.jano.comics.auth.NeedsConsentException
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.ui.UiNaming
import mx.jano.comics.ui.UiStrings

/** Pasos del primer arranque (o del cambio de carpeta raíz). */
sealed interface OnbStep {
    data object Checking : OnbStep
    data class NeedsConnect(val error: String? = null) : OnbStep
    data object WaitingConsent : OnbStep
    data object LookingForRoot : OnbStep
    data class Proposal(val folder: DriveFile) : OnbStep
    /** Navegador de carpetas: [trail] vacío = Mi unidad; [folders] null mientras carga. */
    data class Browsing(val trail: List<DriveFile>, val folders: List<DriveFile>?, val error: String? = null) : OnbStep
    data object Saving : OnbStep
    data class Done(val rootId: String) : OnbStep
    data class Failed(val message: String) : OnbStep
}

/**
 * Conectar Google Drive como la sonda (NeedsConsentException → hoja de Play Services → completeConsent) y elegir la
 * carpeta raíz: «Cómics» en Mi unidad si existe; si no, un navegador de carpetas. Guarda rootFolderId/rootFolderName.
 * [changeRoot]: llegado desde Ajustes; salta la propuesta y abre el navegador.
 */
class OnboardingViewModel(private val graph: AppGraph, val changeRoot: Boolean) : ViewModel() {
    private val _step = MutableStateFlow<OnbStep>(OnbStep.Checking)
    val step: StateFlow<OnbStep> = _step.asStateFlow()

    private val _consent = MutableStateFlow<PendingIntent?>(null)
    /** Hoja de consentimiento pendiente de lanzar (la lanza la pantalla con StartIntentSenderForResult). */
    val consent: StateFlow<PendingIntent?> = _consent.asStateFlow()

    private var job: Job? = null

    init {
        start()
    }

    /** Comprueba en silencio si ya hay token; si hace falta pantalla, enseña «Conectar Google Drive». */
    fun start() = work {
        _step.value = OnbStep.Checking
        try {
            graph.tokens.accessToken()
            afterToken()
        } catch (e: NeedsConsentException) {
            _step.value = OnbStep.NeedsConnect()
        }
    }

    /** Botón «Conectar Google Drive». */
    fun connect() = work {
        _step.value = OnbStep.Checking
        try {
            graph.tokens.accessToken()
            afterToken()
        } catch (e: NeedsConsentException) {
            val pi = e.pendingIntent
            if (pi == null) {
                _step.value = OnbStep.NeedsConnect(UiStrings.onbError(e.message))
            } else {
                _consent.value = pi
                _step.value = OnbStep.WaitingConsent
            }
        }
    }

    fun onConsentLaunched() {
        _consent.value = null
    }

    fun onConsentLaunchFailed(t: Throwable) {
        _consent.value = null
        _step.value = OnbStep.NeedsConnect(UiStrings.onbError(t.message))
    }

    fun onConsentResult(resultCode: Int, data: Intent?) = work {
        if (resultCode != Activity.RESULT_OK) {
            _step.value = OnbStep.NeedsConnect(UiStrings.ONB_CONSENT_DENIED)
            return@work
        }
        _step.value = OnbStep.Checking
        try {
            graph.tokens.completeConsent(data)
            graph.tokens.accessToken()
            afterToken()
        } catch (e: NeedsConsentException) {
            // Sin relanzar la hoja: evita un bucle si el usuario desmarcó los permisos.
            _step.value = OnbStep.NeedsConnect(UiStrings.ONB_CONSENT_DENIED)
        }
    }

    private suspend fun afterToken() {
        val app = graph.settings.app.value
        val current = app.rootFolderId
        if (!changeRoot && current != null) {
            _step.value = OnbStep.Done(current)
            return
        }
        if (changeRoot) {
            loadFolders(emptyList())
            return
        }
        _step.value = OnbStep.LookingForRoot
        val found = OnboardingLogic.rootNameCandidates().firstNotNullOfOrNull { name ->
            try {
                graph.drive.findFolder(name, "root")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (found != null) _step.value = OnbStep.Proposal(found) else loadFolders(emptyList())
    }

    /** «Elegir otra»: el navegador desde Mi unidad. */
    fun browseFromRoot() = work { loadFolders(emptyList()) }

    fun enter(folder: DriveFile) {
        val s = _step.value as? OnbStep.Browsing ?: return
        work { loadFolders(s.trail + folder) }
    }

    fun up() {
        val s = _step.value as? OnbStep.Browsing ?: return
        if (s.trail.isEmpty()) return
        work { loadFolders(s.trail.dropLast(1)) }
    }

    fun retryBrowse() {
        val s = _step.value as? OnbStep.Browsing ?: return
        work { loadFolders(s.trail) }
    }

    private suspend fun loadFolders(trail: List<DriveFile>) {
        _step.value = OnbStep.Browsing(trail, null)
        val id = trail.lastOrNull()?.id ?: MY_DRIVE
        _step.value = try {
            OnbStep.Browsing(trail, OnboardingLogic.foldersOnly(graph.drive.listChildren(id), UiNaming.natural))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            OnbStep.Browsing(trail, emptyList(), UiStrings.onbError(e.message))
        }
    }

    /** «Usar esta carpeta»: la guarda como raíz. La raíz de la interfaz relanza el escaneo al ver la raíz nueva. */
    fun use(folder: DriveFile) = work {
        _step.value = OnbStep.Saving
        graph.settings.updateApp { it.copy(rootFolderId = folder.id, rootFolderName = folder.name) }
        _step.value = OnbStep.Done(folder.id)
    }

    fun retry() = start()

    /**
     * La pantalla vuelve a mostrarse (p. ej. el token caducó y la raíz de la interfaz la reabre): si este ViewModel ya
     * había terminado, empieza otra vez en vez de dar por buena la conexión anterior.
     */
    fun onShown() {
        if (_step.value is OnbStep.Done) {
            _step.value = OnbStep.Checking
            start()
        }
    }

    /** Una tarea a la vez; un error inesperado acaba en [OnbStep.Failed] (con «Reintentar»). */
    private fun work(block: suspend () -> Unit) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                _step.value = OnbStep.Failed(UiStrings.onbError(t.message))
            }
        }
    }

    private companion object {
        /** Alias de la API de Drive para la raíz de Mi unidad. */
        const val MY_DRIVE = "root"
    }
}
