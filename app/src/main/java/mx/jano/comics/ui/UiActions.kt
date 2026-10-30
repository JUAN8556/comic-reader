package mx.jano.comics.ui

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import mx.jano.comics.AppGraph
import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.ui.library.LibraryFilter

/**
 * Acciones del usuario sobre la biblioteca, en el ámbito de la aplicación (no se cortan al cambiar de pantalla).
 * Cada una avisa con un mensaje corto en [messages] (Snackbar en la raíz), también si falla.
 */
class UiActions(private val graph: AppGraph, private val scope: CoroutineScope, private val prefs: UiPrefsStore) {
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun say(message: String) {
        _messages.tryEmit(message)
    }

    /** Vuelve a escanear Drive y, después, relee .indice (fichas y portadas) por si la migración los cambió. */
    fun rescan() = run(UiStrings.ERR_SCAN, quiet = true) {
        graph.library.rescan()
        runCatching { graph.metadata.refreshFromDrive() }
        runCatching { graph.covers.importIndexCovers() }
    }

    /**
     * Marcar leído / no leído. «No leído» deja además la página en 0 para que el libro vuelva a «No leído» y no se quede
     * en «Pág. X de Y».
     */
    fun markFinished(books: List<Book>, finished: Boolean) = run(UiStrings.ERR_MARK) {
        if (books.isEmpty()) return@run
        if (books.size == 1) graph.progress.setFinished(books[0], finished) else graph.progress.setFinished(books, finished)
        if (!finished) {
            for (b in books) {
                val p = graph.progress.get(b.contentKey) ?: continue
                if (p.page > 0) graph.progress.update(b, 0, p.pageCount)
            }
        }
        say(UiStrings.msgMarked(books.size, finished))
    }

    fun pin(books: List<Book>) = run(UiStrings.ERR_PIN) {
        if (books.isEmpty()) return@run
        val missing = graph.pins.pin(books)
        say(UiStrings.msgPinned(books.size, missing))
    }

    fun unpin(books: List<Book>) = run(UiStrings.ERR_UNPIN) {
        if (books.isEmpty()) return@run
        graph.pins.unpin(books)
        say(UiStrings.msgUnpinned(books.size))
    }

    /** Borra de la tablet (quita antes el fijado, si lo tenía, para que no vuelva a bajarse). */
    fun evict(books: List<Book>) = run(UiStrings.ERR_EVICT) {
        val local = books.filter { graph.cache.availability(it) != Availability.CLOUD }
        if (local.isEmpty()) return@run
        val pinned = local.filter { graph.pins.isPinned(it) }
        if (pinned.isNotEmpty()) graph.pins.unpin(pinned)
        for (b in local) {
            graph.cache.cancelDownload(b.id)
            graph.cache.evict(b)
        }
        say(UiStrings.msgEvicted(local.size))
    }

    /** RTL de un libro (ruta completa) o carpeta (su ruta). En la raíz (ruta vacía) es el ajuste global. */
    fun setRtl(path: String, rtl: Boolean?) = run(UiStrings.ERR_RTL) {
        if (path.isEmpty()) {
            graph.settings.updateReader { it.copy(rtlGlobal = rtl ?: false) }
        } else {
            graph.settings.setRtl(path, rtl)
        }
        say(UiStrings.msgRtl(rtl))
    }

    fun setFilter(filter: LibraryFilter) {
        scope.launch { runCatching { prefs.update { it.copy(libraryFilter = filter) } } }
    }

    fun backup() = run(UiStrings.ERR_BACKUP) {
        graph.progress.backupToDrive().getOrThrow()
        prefs.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
        say(UiStrings.SET_BACKUP_DONE)
    }

    fun restore() = run(UiStrings.ERR_RESTORE) {
        val n = graph.progress.restoreFromDrive().getOrThrow()
        say(UiStrings.setRestoreDone(n))
    }

    fun clearCache() = run(UiStrings.ERR_CLEAR_CACHE) {
        graph.cache.clearAll()
        say(UiStrings.SET_CLEAR_CACHE_DONE)
    }

    /** Estado de lectura con el mapa de progreso ya en memoria (sin ir al store libro a libro). */
    fun stateOf(book: Book): ReadState = graph.progress.all.value[book.contentKey]?.state ?: ReadState.UNREAD

    private fun run(what: String, quiet: Boolean = false, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "$what: ${t.javaClass.simpleName}: ${t.message}")
                if (!quiet) say(UiStrings.msgError(what, t.message))
            }
        }
    }

    private companion object {
        const val TAG = "Comics"
    }
}
