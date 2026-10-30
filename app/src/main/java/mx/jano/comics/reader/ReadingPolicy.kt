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
     * Página por la que se abre un libro: la guardada, acotada si el archivo cambió y tiene menos páginas.
     *
     * Un libro terminado se abre por el principio solo si se dejó en su final (volver a leerlo). Si se está releyendo
     * (sigue «Leído», porque el almacén conserva la marca, pero la página guardada va por la mitad), se retoma ahí.
     */
    fun startPage(saved: ReadingProgress?, pageCount: Int): Int {
        if (saved == null || pageCount <= 0) return 0
        if (saved.finished && isAtEnd(saved, pageCount)) return 0
        return saved.page.coerceIn(0, pageCount - 1)
    }

    /** ¿La página guardada de un terminado es su final (o la portada)? Mira el recuento actual y el guardado. */
    private fun isAtEnd(saved: ReadingProgress, pageCount: Int): Boolean =
        saved.page <= 0 ||
            saved.page >= pageCount - 1 ||
            (saved.pageCount > 0 && saved.page >= saved.pageCount - 1)

    /** ¿Ha pasado el lector del 80 % del libro? (entonces se pide el N+1 por adelantado). */
    fun crossedPrefetchThreshold(lastVisiblePage: Int, pageCount: Int, fraction: Double = PREFETCH_FRACTION): Boolean {
        if (pageCount <= 0) return false
        val threshold = ceil(pageCount * fraction).toInt().coerceAtLeast(1)
        return lastVisiblePage + 1 > threshold || lastVisiblePage >= pageCount - 1
    }
}

/**
 * Guarda el progreso con debounce: cada cambio de página reprograma el guardado a [debounceMs]; [flush] lo hace ya
 * (al salir). Cada vez que la posición guardada ve la última página, marca el libro como terminado (el almacén deja
 * entonces la página en la última, y la próxima apertura empieza por la portada). Volver atrás después no repite la
 * marca: el almacén conserva «terminado» y se guarda la página de verdad, para retomar una relectura donde quedó.
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
            if (reachedEnd) runCatching { markFinished(p.pageCount) }
        }
    }
}
