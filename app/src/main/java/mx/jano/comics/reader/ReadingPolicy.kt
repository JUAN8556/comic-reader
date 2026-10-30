package mx.jano.comics.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mx.jano.comics.library.ReadingProgress
import kotlin.math.ceil

/**
 * Dónde está el lector: [page] es la primera página del pliego visible (la que se guarda), [lastPage] la última
 * que se ve (en un pliego doble, la de al lado; en vertical, la última visible). [atEnd] = página de «Siguiente».
 */
data class ReaderPosition(
    val page: Int,
    val lastPage: Int,
    val pageCount: Int,
    val spread: Int = page,
    val spreadCount: Int = pageCount,
    val atEnd: Boolean = false,
) {
    /** ¿Se ve ya la última página del libro? */
    val reachedLastPage: Boolean get() = pageCount > 0 && (atEnd || lastPage >= pageCount - 1)
}

/** Reglas puras del progreso de lectura. */
object ReadingPolicy {
    const val PREFETCH_FRACTION = 0.8

    /**
     * Página por la que se abre un libro: la guardada; un libro terminado se abre por el principio (volver a leerlo),
     * y una página guardada fuera de rango (el archivo cambió) se acota.
     */
    fun startPage(saved: ReadingProgress?, pageCount: Int): Int {
        if (saved == null || pageCount <= 0) return 0
        if (saved.finished) return 0
        return saved.page.coerceIn(0, pageCount - 1)
    }

    /** ¿Ha pasado el lector del 80 % del libro? (entonces se pide el N+1 por adelantado). */
    fun crossedPrefetchThreshold(lastVisiblePage: Int, pageCount: Int, fraction: Double = PREFETCH_FRACTION): Boolean {
        if (pageCount <= 0) return false
        val threshold = ceil(pageCount * fraction).toInt().coerceAtLeast(1)
        return lastVisiblePage + 1 > threshold || lastVisiblePage >= pageCount - 1
    }
}

/**
 * Guarda el progreso con debounce: cada cambio de página reprograma el guardado a [debounceMs]; [flush] lo hace ya
 * (al salir). Al llegar a la última página marca el libro como terminado; a partir de ahí, en esta sesión, cada
 * guardado vuelve a afirmar «terminado» (el almacén puede limpiar la marca al actualizar la página).
 *
 * [scope] debe sobrevivir a la pantalla (el último guardado ocurre cuando el lector ya se fue).
 */
class ProgressSaver(
    private val scope: CoroutineScope,
    private val debounceMs: Long = 500,
    private val save: suspend (page: Int, pageCount: Int) -> Unit,
    private val markFinished: suspend (pageCount: Int) -> Unit,
) {
    private val lock = Any()
    private val mutex = Mutex()
    private var pending: ReaderPosition? = null
    private var lastSaved: Pair<Int, Int>? = null
    private var job: Job? = null
    private var finishedThisSession = false
    @Volatile var closed = false
        private set

    /** Nueva posición del lector. */
    fun onPosition(position: ReaderPosition) {
        if (position.pageCount <= 0) return
        synchronized(lock) {
            if (closed) return
            pending = position
            job?.cancel()
            job = scope.launch {
                delay(debounceMs)
                // Solo la espera se cancela: un guardado empezado termina aunque llegue otra página.
                withContext(NonCancellable) { writePending() }
            }
        }
    }

    /** Guarda ahora lo pendiente (si lo hay) y deja de aceptar posiciones si [close]. */
    fun flush(close: Boolean = false): Job = synchronized(lock) {
        job?.cancel()
        job = null
        if (close) closed = true
        scope.launch { withContext(NonCancellable) { writePending() } }
    }

    private suspend fun writePending() {
        mutex.withLock {
            val p = synchronized(lock) { pending.also { pending = null } } ?: return
            val key = p.page to p.pageCount
            val reachedEnd = p.reachedLastPage
            if (key != lastSaved) {
                if (runCatching { save(p.page, p.pageCount) }.isSuccess) lastSaved = key
            }
            if (reachedEnd) finishedThisSession = true
            if (finishedThisSession) runCatching { markFinished(p.pageCount) }
        }
    }
}
