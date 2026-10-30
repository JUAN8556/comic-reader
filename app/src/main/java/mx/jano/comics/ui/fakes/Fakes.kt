package mx.jano.comics.ui.fakes

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import mx.jano.comics.AppGraph
import mx.jano.comics.auth.TokenProvider
import mx.jano.comics.cache.Availability
import mx.jano.comics.cache.CacheManager
import mx.jano.comics.cache.CacheState
import mx.jano.comics.cache.CachedBook
import mx.jano.comics.cache.DownloadProgress
import mx.jano.comics.cache.PinManager
import mx.jano.comics.cache.PinProgress
import mx.jano.comics.cache.TransferItem
import mx.jano.comics.cache.TransferQueue
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.drive.DriveAbout
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.library.Book
import mx.jano.comics.library.CoverStore
import mx.jano.comics.library.Folder
import mx.jano.comics.library.LibraryIndex
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.library.ScanState
import mx.jano.comics.metadata.IndexEntry
import mx.jano.comics.metadata.MetadataRepository
import mx.jano.comics.metadata.SeriesMeta
import mx.jano.comics.settings.AppSettings
import mx.jano.comics.settings.ReaderSettings
import mx.jano.comics.settings.SettingsStore
import mx.jano.comics.ui.FallbackNatural
import java.io.File

/**
 * Implementaciones en memoria de los contratos, para vistas previas y pruebas JVM de la interfaz.
 * No tocan Android (salvo tipos) ni la red.
 */
class FakeAppGraph(
    index: LibraryIndex = FakeData.index(),
    progress: Map<String, ReadingProgress> = FakeData.progress(index),
    online: Boolean = true,
    local: Set<String> = emptySet(),
    pinned: Set<String> = emptySet(),
    series: Map<String, SeriesMeta> = FakeData.series(),
) : AppGraph {
    override val tokens = FakeTokenProvider()
    override val drive = FakeDriveApi(index)
    override val appData = FakeAppDataStore()
    override val settings = FakeSettingsStore(AppSettings(rootFolderId = index.rootId, rootFolderName = index.rootName))
    override val library = FakeLibraryRepository(index)
    override val progress = FakeProgressStore(progress)
    override val covers = FakeCoverStore()
    override val metadata = FakeMetadataRepository(series)
    override val cache = FakeCacheManager(local, pinned)
    override val pins = FakePinManager(pinned)
    override val transfers = FakeTransferQueue(online)
}

class FakeTokenProvider : TokenProvider {
    override suspend fun accessToken(forceRefresh: Boolean): String = "falso"
    override suspend fun invalidate(token: String) = Unit
    override fun completeConsent(data: Intent?): List<String> = scopes
    override val scopes: List<String> = listOf("drive.readonly", "drive.appdata")
}

class FakeDriveApi(private val index: LibraryIndex) : DriveApi {
    private val folderMime = "application/vnd.google-apps.folder"
    override suspend fun findFolder(name: String, parentId: String): DriveFile? =
        if (parentId == "root" && name == index.rootName) DriveFile(index.rootId, index.rootName, folderMime) else null

    override suspend fun listChildren(folderId: String): List<DriveFile> {
        val parent = if (folderId == "root") null else folderId
        if (parent == null) return listOf(DriveFile(index.rootId, index.rootName, folderMime))
        return index.folders.filter { it.parentId == parent }.map { DriveFile(it.id, it.name, folderMime) } +
            index.books.filter { it.folderId == parent }.map { DriveFile(it.id, it.name, "application/zip", it.size) }
    }

    override suspend fun download(fileId: String, dest: File, resume: Boolean, onProgress: (Long, Long?) -> Unit) =
        throw UnsupportedOperationException("FakeDriveApi no descarga")

    override suspend fun about(): DriveAbout = DriveAbout(userEmail = null)
}

class FakeAppDataStore : AppDataStore {
    private val files = LinkedHashMap<String, ByteArray>()
    override suspend fun list(): List<DriveFile> = files.keys.map { DriveFile(it, it, "application/json", modifiedTime = "2026-10-30T22:15:00Z") }
    override suspend fun put(name: String, bytes: ByteArray, mimeType: String): DriveFile {
        files[name] = bytes
        return DriveFile(name, name, mimeType)
    }
    override suspend fun get(name: String): ByteArray? = files[name]
}

class FakeSettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val _app = MutableStateFlow(initial)
    private val _reader = MutableStateFlow(ReaderSettings())
    private val rtl = HashMap<String, Boolean>()
    override val app: StateFlow<AppSettings> = _app.asStateFlow()
    override val reader: StateFlow<ReaderSettings> = _reader.asStateFlow()
    override suspend fun updateApp(transform: (AppSettings) -> AppSettings) = _app.update(transform)
    override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) = _reader.update(transform)

    /** Libro > carpeta más cercana > global. */
    override fun rtlFor(bookPath: String): Boolean {
        var p = bookPath
        while (true) {
            rtl[p]?.let { return it }
            if (!p.contains('/')) break
            p = p.substringBeforeLast('/')
        }
        return _reader.value.rtlGlobal
    }

    override suspend fun setRtl(path: String, rtl: Boolean?) {
        if (rtl == null) this.rtl.remove(path) else this.rtl[path] = rtl
    }
}

class FakeLibraryRepository(initial: LibraryIndex?) : LibraryRepository {
    private val _index = MutableStateFlow(initial)
    private val _scan = MutableStateFlow<ScanState>(ScanState.Idle)
    override val index: StateFlow<LibraryIndex?> = _index.asStateFlow()
    override val scanState: StateFlow<ScanState> = _scan.asStateFlow()
    private val byPath = compareBy<String, String>(FallbackNatural) { it }

    override suspend fun rescan(): LibraryIndex {
        val idx = _index.value ?: FakeData.index()
        _scan.value = ScanState.Done(idx.folders.size, idx.books.size, 1200)
        _index.value = idx
        return idx
    }

    override fun folder(id: String): Folder? = _index.value?.folders?.firstOrNull { it.id == id }
    override fun book(id: String): Book? = _index.value?.books?.firstOrNull { it.id == id }

    override fun children(folderId: String): Pair<List<Folder>, List<Book>> {
        val idx = _index.value ?: return emptyList<Folder>() to emptyList()
        val folders = idx.folders.filter { it.parentId == folderId }.sortedWith(compareBy(FallbackNatural) { it.name })
        val books = idx.books.filter { it.folderId == folderId }.sortedWith(compareBy(FallbackNatural) { it.name })
        return folders to books
    }

    override fun booksUnder(folderId: String): List<Book> {
        val idx = _index.value ?: return emptyList()
        val f = folder(folderId) ?: return emptyList()
        val prefix = if (f.path.isEmpty()) "" else f.path + "/"
        return idx.books.filter { it.path.startsWith(prefix) }.sortedWith(compareBy(byPath) { it.path })
    }

    override fun nextAfter(book: Book): Book? {
        val siblings = children(book.folderId).second
        val i = siblings.indexOfFirst { it.id == book.id }
        return if (i >= 0) siblings.getOrNull(i + 1) else null
    }

    override fun previousBefore(book: Book): Book? {
        val siblings = children(book.folderId).second
        val i = siblings.indexOfFirst { it.id == book.id }
        return if (i > 0) siblings[i - 1] else null
    }
}

class FakeProgressStore(initial: Map<String, ReadingProgress> = emptyMap()) : ProgressStore {
    private val _all = MutableStateFlow(initial)
    override val all: StateFlow<Map<String, ReadingProgress>> = _all.asStateFlow()
    override fun get(contentKey: String): ReadingProgress? = _all.value[contentKey]
    override fun stateOf(book: Book): ReadState = get(book.contentKey)?.state ?: ReadState.UNREAD

    override suspend fun update(book: Book, page: Int, pageCount: Int) {
        val old = get(book.contentKey)
        _all.update {
            it + (book.contentKey to ReadingProgress(book.contentKey, page, pageCount, System.currentTimeMillis(), old?.finished ?: false))
        }
    }

    override suspend fun setFinished(book: Book, finished: Boolean, pageCount: Int?) {
        val old = get(book.contentKey)
        val count = pageCount ?: old?.pageCount ?: 0
        _all.update {
            it + (book.contentKey to ReadingProgress(book.contentKey, old?.page ?: 0, count, System.currentTimeMillis(), finished))
        }
    }

    override suspend fun setFinished(books: List<Book>, finished: Boolean) = books.forEach { setFinished(it, finished, null) }
    override fun recent(limit: Int): List<ReadingProgress> = _all.value.values.sortedByDescending { it.updatedAt }.take(limit)
    override suspend fun backupToDrive(): Result<Unit> = Result.success(Unit)
    override suspend fun restoreFromDrive(): Result<Int> = Result.success(_all.value.size)
}

class FakeCoverStore : CoverStore {
    override fun coverFile(book: Book): File? = null
    override suspend fun ensureCover(book: Book): File? = null
    override suspend fun importIndexCovers(): Int = 0
}

class FakeMetadataRepository(private val series: Map<String, SeriesMeta>) : MetadataRepository {
    override suspend fun refreshFromDrive(): Result<Unit> = Result.success(Unit)
    override fun entryFor(book: Book): IndexEntry? = null
    override fun seriesFor(folderPath: String): SeriesMeta? = series[folderPath]
    override fun missingNumbers(folderPath: String, books: List<Book>): List<Int> {
        val total = series[folderPath]?.numeros_totales ?: return emptyList()
        val present = books.mapNotNull { Regex("(\\d+)").find(it.name)?.value?.toIntOrNull() }.toSet()
        return (1..total).filterNot { it in present }
    }
    override val loaded: Boolean = true
}

class FakeCacheManager(local: Set<String>, pinned: Set<String>) : CacheManager {
    private val localIds = (local + pinned).toMutableSet()
    private val pinnedIds = pinned.toMutableSet()
    private val _state = MutableStateFlow(snapshot())
    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val state: StateFlow<CacheState> = _state.asStateFlow()
    override val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    private fun snapshot() = CacheState(
        usedBytes = localIds.size * 40L * 1024 * 1024,
        limitBytes = 20L * 1024 * 1024 * 1024,
        deviceFreeBytes = 80L * 1024 * 1024 * 1024,
        minFreeBytes = 15L * 1024 * 1024 * 1024,
        entries = localIds.map { CachedBook(it, it, "/falso/$it", 40L * 1024 * 1024, 0, it in pinnedIds) },
    )

    override fun availability(book: Book): Availability = when {
        book.id in _downloads.value -> Availability.DOWNLOADING
        book.id in pinnedIds -> Availability.PINNED
        book.id in localIds -> Availability.LOCAL
        else -> Availability.CLOUD
    }

    override fun localFile(book: Book): File? = null
    override suspend fun ensureLocal(book: Book, onProgress: (DownloadProgress) -> Unit): File =
        throw UnsupportedOperationException("FakeCacheManager no descarga")
    override fun cancelDownload(bookId: String) = Unit
    override suspend fun evict(book: Book) {
        localIds -= book.id
        pinnedIds -= book.id
        _state.value = snapshot()
    }
    override suspend fun enforceLimits(protect: Set<String>) = Unit
    override fun pageCacheDir(book: Book): File = File("/falso/paginas/${book.id}")
    override suspend fun clearAll() {
        localIds.clear()
        pinnedIds.clear()
        _state.value = snapshot()
    }
}

class FakePinManager(initial: Set<String>) : PinManager {
    private val _pins = MutableStateFlow(initial)
    override val pins: StateFlow<Set<String>> = _pins.asStateFlow()
    override val progress: StateFlow<PinProgress?> = MutableStateFlow(null)
    override suspend fun pin(books: List<Book>): Long {
        _pins.update { it + books.map(Book::id) }
        return books.sumOf { it.size }
    }
    override suspend fun unpin(books: List<Book>) = _pins.update { it - books.map(Book::id).toSet() }
    override fun isPinned(book: Book): Boolean = book.id in _pins.value
    override fun pause() = Unit
    override fun resume() = Unit
    override fun cancel() = Unit
}

class FakeTransferQueue(online: Boolean) : TransferQueue {
    override val active: StateFlow<List<TransferItem>> = MutableStateFlow(emptyList())
    override fun prefetch(book: Book) = Unit
    override fun cancel(bookId: String) = Unit
    override val isOnline: StateFlow<Boolean> = MutableStateFlow(online)
    override val isUnmetered: StateFlow<Boolean> = MutableStateFlow(online)
}
