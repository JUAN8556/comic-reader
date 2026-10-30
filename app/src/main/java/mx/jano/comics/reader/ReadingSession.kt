package mx.jano.comics.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import mx.jano.comics.cache.TransferQueue
import mx.jano.comics.library.Book
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.settings.SettingsStore

/**
 * Lo que la ruta hace con cada posición del lector, sin Compose (se prueba con dobles de los contratos):
 * progreso con debounce y al cerrar ([ProgressSaver]), «terminado» en la última página, y el prefetch del N+1
 * una sola vez al pasar del 80 % (si está activado; Wi-Fi o no lo decide la cola de transferencias).
 */
internal class ReadingSession(
    private val book: Book,
    private val next: Book?,
    private val progress: ProgressStore,
    private val transfers: TransferQueue,
    private val prefetchEnabled: () -> Boolean,
    scope: CoroutineScope,
    debounceMs: Long = 500,
) {
    private val saver = ProgressSaver(
        scope = scope,
        debounceMs = debounceMs,
        save = { page, count -> progress.update(book, page, count) },
        markFinished = { count -> progress.setFinished(book, true, count) },
    )
    @Volatile private var prefetched = false

    /** Página de entrada: la que se estaba viendo antes de recrear la actividad, o la del almacén de progreso. */
    fun startPage(pageCount: Int, restoredPage: Int = -1): Int =
        if (restoredPage in 0 until pageCount) restoredPage
        else ReadingPolicy.startPage(runCatching { progress.get(book.contentKey) }.getOrNull(), pageCount)

    fun onPosition(position: ReaderPosition) {
        saver.onPosition(position)
        val next = next
        if (!prefetched && next != null && prefetchEnabled() &&
            ReadingPolicy.crossedPrefetchThreshold(position.lastPage, position.pageCount)
        ) {
            prefetched = true
            ReaderTrace.log("pasado el 80 %: se pide el siguiente número por adelantado")
            runCatching { transfers.prefetch(next) }
        }
    }

    /** Al salir: guarda ya lo pendiente y deja de aceptar posiciones. */
    fun close(): Job = saver.flush(close = true)
}

/** RTL efectivo de un libro y lo que heredaría sin valor propio (ver [MangaResolution]). */
internal data class MangaState(val rtl: Boolean, val inherited: Boolean)

internal fun SettingsStore.mangaStateFor(bookPath: String): MangaState {
    val global = runCatching { reader.value.rtlGlobal }.getOrDefault(false)
    val rtl = runCatching { rtlFor(bookPath) }.getOrDefault(global)
    val inherited = runCatching { rtlFor(MangaResolution.inheritedProbePath(bookPath)) }.getOrDefault(global)
    return MangaState(rtl, inherited)
}
