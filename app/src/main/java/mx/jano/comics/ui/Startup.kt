package mx.jano.comics.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import mx.jano.comics.auth.NeedsConsentException
import mx.jano.comics.library.LibraryIndex

/**
 * Tareas al abrir la biblioteca de una raíz (una vez por proceso y raíz): token en silencio (si hace falta pantalla,
 * vuelve al primer arranque), escaneo si el índice falta, es de otra raíz o es viejo, y después la ficha de series y
 * las portadas del índice. Sin red no pasa nada: la biblioteca sigue con el índice local.
 */
object Startup {
    /** Un índice de más de 6 h se refresca solo al arrancar (Drive cambia cuando la migración sube cosas). */
    const val RESCAN_AFTER_MS = 6 * 60 * 60 * 1000L

    fun needsRescan(index: LibraryIndex?, rootId: String, now: Long): Boolean =
        index == null || index.rootId != rootId || now - index.scannedAt > RESCAN_AFTER_MS

    fun launch(env: UiEnv, rootId: String, onNeedsConsent: () -> Unit) {
        val graph = env.graph
        env.scope.launch {
            try {
                graph.tokens.accessToken()
            } catch (e: NeedsConsentException) {
                onNeedsConsent()
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.i(TAG, "arranque sin token (${t.javaClass.simpleName}): se sigue con el índice local")
            }
            if (needsRescan(graph.library.index.value, rootId, System.currentTimeMillis()) && graph.transfers.isOnline.value) {
                quietly("escaneo") { graph.library.rescan() }
                env.covers.forgetFailures()
            }
            quietly("índice de series") { graph.metadata.refreshFromDrive() }
            quietly("portadas del índice") { graph.covers.importIndexCovers() }
        }
    }

    private suspend fun quietly(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "arranque: $what falló (${t.javaClass.simpleName}: ${t.message})")
        }
    }

    private const val TAG = "Comics"
}
