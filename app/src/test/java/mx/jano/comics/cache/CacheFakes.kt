package mx.jano.comics.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mx.jano.comics.drive.DriveAbout
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.drive.DriveRestApi
import mx.jano.comics.drive.PartialVersion
import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.library.LibraryIndex
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.library.ScanState
import mx.jano.comics.settings.AppSettings
import mx.jano.comics.settings.ReaderSettings
import mx.jano.comics.settings.SettingsStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeClock(var time: Long = 1_000_000L) : Clock {
    override fun now(): Long = time
    fun advance(ms: Long) {
        time += ms
    }
}

class MemoryStore<T>(var value: T? = null) : JsonStateStore<T> {
    var saves = 0
    override fun load(): T? = value
    override fun save(value: T) {
        saves++
        this.value = value
    }
}

/** Volumen de [capacity] bytes: lo libre baja con lo que haya en las carpetas vigiladas (como en la tablet). */
class FakeVolume(var capacity: Long, private vararg val dirs: File) : StorageStats {
    var extraUsed = 0L
    override fun freeBytes(dir: File): Long =
        capacity - extraUsed - dirs.sumOf { d -> d.walkTopDown().filter { it.isFile }.sumOf { it.length() } }
}

class FakeSettings(app: AppSettings = AppSettings()) : SettingsStore {
    override val app = MutableStateFlow(app)
    override val reader = MutableStateFlow(ReaderSettings())
    override suspend fun updateApp(transform: (AppSettings) -> AppSettings) {
        app.value = transform(app.value)
    }

    override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        reader.value = transform(reader.value)
    }

    override fun rtlFor(bookPath: String): Boolean = false
    override suspend fun setRtl(path: String, rtl: Boolean?) = Unit
}

class FakeProgress : ProgressStore {
    val map = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
    override val all: StateFlow<Map<String, ReadingProgress>> = map

    fun set(book: Book, state: ReadState) {
        val p = when (state) {
            ReadState.UNREAD -> ReadingProgress(book.contentKey, 0, 20, 0, false)
            ReadState.READING -> ReadingProgress(book.contentKey, 5, 20, 0, false)
            ReadState.FINISHED -> ReadingProgress(book.contentKey, 19, 20, 0, true)
        }
        map.value = map.value + (book.contentKey to p)
    }

    override fun get(contentKey: String): ReadingProgress? = map.value[contentKey]
    override fun stateOf(book: Book): ReadState = get(book.contentKey)?.state ?: ReadState.UNREAD
    override suspend fun update(book: Book, page: Int, pageCount: Int) = Unit
    override suspend fun setFinished(book: Book, finished: Boolean, pageCount: Int?) = Unit
    override suspend fun setFinished(books: List<Book>, finished: Boolean) = Unit
    override fun recent(limit: Int): List<ReadingProgress> = emptyList()
    override suspend fun backupToDrive(): Result<Unit> = Result.success(Unit)
    override suspend fun restoreFromDrive(): Result<Int> = Result.success(0)
}

class FakeLibrary(books: List<Book> = emptyList()) : LibraryRepository {
    val books = ConcurrentHashMap<String, Book>().apply { books.forEach { put(it.id, it) } }
    override val index: StateFlow<LibraryIndex?> = MutableStateFlow(null)
    override val scanState: StateFlow<ScanState> = MutableStateFlow(ScanState.Idle)
    override suspend fun rescan(): LibraryIndex = error("no se usa")
    override fun folder(id: String): Folder? = null
    override fun book(id: String): Book? = books[id]
    override fun children(folderId: String): Pair<List<Folder>, List<Book>> = emptyList<Folder>() to emptyList()
    override fun booksUnder(folderId: String): List<Book> = emptyList()
    override fun nextAfter(book: Book): Book? = null
    override fun previousBefore(book: Book): Book? = null
}

/**
 * Drive de mentira con el mismo contrato de disco que [DriveRestApi.download]: escribe la huella `<dest>.meta`
 * antes del primer byte y, si hay parcial de la misma versión y resume, sigue desde ahí.
 */
class FakeDrive : DriveApi {
    val contents = ConcurrentHashMap<String, ByteArray>()
    /** md5 que «dice» Drive (por defecto el real del contenido). */
    val claimedMd5 = ConcurrentHashMap<String, String>()
    /** Bytes que se sirven en lugar de los buenos (para simular corrupción), las [corruptTimes] primeras veces. */
    val corruptTimes = ConcurrentHashMap<String, Int>()
    /** Corta la conexión tras escribir tantos bytes (una vez). */
    val cutAfter = ConcurrentHashMap<String, Int>()
    /** Error a lanzar al pedir el fichero. */
    val failWith = ConcurrentHashMap<String, Exception>()
    /** Si está, la descarga espera a que se complete antes de escribir. */
    @Volatile
    var gate: CompletableDeferred<Unit>? = null
    val calls = AtomicInteger()
    val offsets = mutableListOf<Long>()

    fun put(id: String, bytes: ByteArray) {
        contents[id] = bytes
    }

    fun md5Of(id: String): String = claimedMd5[id] ?: md5(contents.getValue(id))

    override suspend fun findFolder(name: String, parentId: String): DriveFile? = null
    override suspend fun listChildren(folderId: String): List<DriveFile> = emptyList()
    override suspend fun about(): DriveAbout = DriveAbout()

    override suspend fun download(fileId: String, dest: File, resume: Boolean, onProgress: (Long, Long?) -> Unit) {
        calls.incrementAndGet()
        failWith[fileId]?.let { throw it }
        val good = contents[fileId] ?: throw DriveHttpException(404, "notFound", "no está")
        val remote = PartialVersion(fileId = fileId, headRevisionId = "r1", md5Checksum = md5Of(fileId), size = good.size.toLong())
        val stamp = DriveRestApi.partialVersionFile(dest)
        dest.absoluteFile.parentFile?.mkdirs()
        if (resume && dest.isFile && dest.length() > 0 &&
            DriveRestApi.staleReason(DriveRestApi.readPartialVersion(stamp), remote, dest.length()) != null
        ) {
            dest.delete()
        }
        DriveRestApi.writePartialVersion(stamp, remote)
        gate?.await()
        val offset = if (resume && dest.isFile) dest.length() else 0L
        synchronized(offsets) { offsets += offset }
        val corrupt = (corruptTimes[fileId] ?: 0) > 0
        if (corrupt) corruptTimes[fileId] = corruptTimes.getValue(fileId) - 1
        val body = if (corrupt) ByteArray(good.size) { 7 } else good
        val cut = cutAfter.remove(fileId)
        FileOutputStream(dest, offset > 0).use { out ->
            var written = offset
            onProgress(written, good.size.toLong())
            var i = offset.toInt()
            while (i < body.size) {
                val n = minOf(1024, body.size - i)
                out.write(body, i, n)
                i += n
                written += n
                onProgress(written, good.size.toLong())
                if (cut != null && written - offset >= cut) throw IOException("conexión cortada")
            }
        }
    }

    companion object {
        fun md5(bytes: ByteArray): String =
            MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Un CBZ mínimo (por la firma basta para el sniffer) de [size] bytes. */
fun zipLike(size: Int, fill: Byte = 1): ByteArray = ByteArray(size) { fill }.also {
    it[0] = 0x50; it[1] = 0x4B; it[2] = 0x03; it[3] = 0x04
}

/** Un CBZ de [size] bytes con el id dentro: contenido (y md5, y clave de contenido) distinto por libro. */
fun zipFor(id: String, size: Int): ByteArray = zipLike(size).also { id.toByteArray().copyInto(it, destinationOffset = 8) }

fun pdfLike(size: Int): ByteArray = ByteArray(size) { 2 }.also { "%PDF-1.7".toByteArray().copyInto(it) }

fun bookOf(drive: FakeDrive, id: String, name: String = "$id.cbz", folder: String = "Serie"): Book {
    val bytes = drive.contents.getValue(id)
    return Book(
        id = id,
        name = name,
        folderId = "f-$folder",
        path = "$folder/$name",
        size = bytes.size.toLong(),
        md5 = drive.md5Of(id),
    )
}

/**
 * Un DiskCacheManager de prueba sobre [dir]: «1 GB» de Ajustes son 1 000 bytes y el suelo para abrir, 100 bytes.
 * [manager] crea uno nuevo sobre las mismas carpetas y el mismo cache.json (como al reiniciar la app).
 */
class CacheEnv(
    dir: File,
    private val root: CoroutineScope,
    limitGb: Int = 20,
    minFreeGb: Int = 15,
    capacity: Long = 1_000_000L,
    val adopt: Boolean = true,
) {
    val drive = FakeDrive()
    val settings = FakeSettings(AppSettings(cacheLimitGb = limitGb, minFreeGb = minFreeGb))
    val store = MemoryStore<CacheSnapshot>()
    val clock = FakeClock()
    val progress = FakeProgress()
    val library = FakeLibrary()
    val books = File(dir, "books")
    val pages = File(dir, "pages")
    val volume = FakeVolume(capacity, books, pages)
    private var scope: CoroutineScope? = null

    /** [viaLookup]: el progreso no se pasa, se descubre al desalojar (como con la factoría de 4 parámetros). */
    fun manager(viaLookup: Boolean = false): DiskCacheManager {
        scope?.cancel()
        val s = CoroutineScope(SupervisorJob(root.coroutineContext[Job]) + Dispatchers.IO)
        scope = s
        return DiskCacheManager(
            booksDir = books,
            pagesRoot = pages,
            drive = drive,
            settings = settings,
            library = library,
            store = store,
            storage = volume,
            clock = clock,
            scope = s,
            io = Dispatchers.IO,
            adoptOrphans = adopt,
            log = { println(it) },
            progress = if (viaLookup) null else progress,
            bytesPerGb = 1_000,
            readFloorBytes = 100,
            progressLookup = { if (viaLookup) progress else null },
        )
    }

    fun book(id: String, bytes: ByteArray = zipFor(id, 3_000), name: String = "$id.cbz"): Book {
        drive.put(id, bytes)
        return bookOf(drive, id, name)
    }
}
