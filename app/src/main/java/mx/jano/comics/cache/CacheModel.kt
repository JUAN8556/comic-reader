package mx.jano.comics.cache

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.library.Book
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.settings.SettingsStore
import java.io.File

/**
 * CONTRATO COMÚN del MVP. Caché privada (getExternalFilesDir, sin permisos de almacenamiento), LRU con tope
 * y margen libre; lo fijado no se desaloja; nunca el libro abierto ni su N+1.
 */
enum class Availability { CLOUD, DOWNLOADING, LOCAL, PINNED }

@Serializable
data class CachedBook(
    val bookId: String,
    val contentKey: String,
    val file: String,               // ruta absoluta del archivo completo
    val bytes: Long,
    val lastOpened: Long,
    val pinned: Boolean,
)

data class CacheState(
    val usedBytes: Long,
    val limitBytes: Long,
    val deviceFreeBytes: Long,
    val minFreeBytes: Long,
    val entries: List<CachedBook>,
)

data class DownloadProgress(val bookId: String, val bytes: Long, val total: Long?, val bytesPerSecond: Long)

interface CacheManager {
    val state: StateFlow<CacheState>
    val downloads: StateFlow<Map<String, DownloadProgress>>    // por bookId
    fun availability(book: Book): Availability
    fun localFile(book: Book): File?
    /** Descarga (Range reanudable, versión comprobada, md5 verificado) si falta; marca lastOpened. */
    suspend fun ensureLocal(book: Book, onProgress: (DownloadProgress) -> Unit = {}): File
    fun cancelDownload(bookId: String)
    suspend fun evict(book: Book)
    /** Aplica tope y margen (LRU: leídos antes que no empezados, estos antes que «leyendo»; nunca fijados ni el abierto/N+1). */
    suspend fun enforceLimits(protect: Set<String> = emptySet())
    /** Carpeta de páginas extraídas de un libro (se borra con el libro). */
    fun pageCacheDir(book: Book): File
    suspend fun clearAll()
}

data class PinProgress(val total: Int, val done: Int, val bytesDone: Long, val bytesTotal: Long, val current: String?)

interface PinManager {
    val pins: StateFlow<Set<String>>                           // bookIds fijados
    val progress: StateFlow<PinProgress?>
    /** Fija libros (descarga en segundo plano con JobScheduler UIDT + notificación). Devuelve los bytes que faltan por bajar. */
    suspend fun pin(books: List<Book>): Long
    suspend fun unpin(books: List<Book>)
    fun isPinned(book: Book): Boolean
    fun pause(); fun resume(); fun cancel()
}

data class TransferItem(val bookId: String, val reason: String, val bytes: Long, val total: Long?)

interface TransferQueue {
    val active: StateFlow<List<TransferItem>>
    /** Prefetch de N+1 (prioridad baja, solo Wi-Fi si así está configurado; WorkManager). */
    fun prefetch(book: Book)
    fun cancel(bookId: String)
    val isOnline: StateFlow<Boolean>
    val isUnmetered: StateFlow<Boolean>
}

/** Los rellena el frente «cache». */
object CacheFactories {
    fun cacheManager(context: Context, drive: DriveApi, settings: SettingsStore, library: LibraryRepository): CacheManager = TODO("mvp/cache")
    fun pinManager(context: Context, cache: CacheManager, library: LibraryRepository): PinManager = TODO("mvp/cache")
    fun transferQueue(context: Context, cache: CacheManager, settings: SettingsStore): TransferQueue = TODO("mvp/cache")
}
