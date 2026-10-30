package mx.jano.comics.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mx.jano.comics.library.Book
import mx.jano.comics.library.CoverStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Portadas para la rejilla: primero el disco ([CoverStore.coverFile]); si falta, [CoverStore.ensureCover] con pocas
 * descargas a la vez (al desplazar deprisa se cancelan las celdas que salen). Recuerda los fallos de esta sesión para
 * no reintentar en bucle; [forgetFailures] los olvida (tras volver a escanear).
 */
class CoverLoader(private val store: CoverStore, parallel: Int = 3) {
    private val permits = Semaphore(parallel)
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun load(book: Book): File? {
        val key = book.contentKey
        withContext(Dispatchers.IO) { runCatching { store.coverFile(book) }.getOrNull() }?.let { return it }
        if (key in failed) return null
        return permits.withPermit {
            try {
                withContext(Dispatchers.IO) { store.ensureCover(book) }.also { if (it == null) failed += key }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                failed += key
                null
            }
        }
    }

    fun forgetFailures() = failed.clear()
}
