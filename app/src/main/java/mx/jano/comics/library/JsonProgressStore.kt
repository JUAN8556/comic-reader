package mx.jano.comics.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.settings.AtomicFiles
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Lo que se guarda en files/progress.json y, con el mismo formato, en progress.json del appDataFolder de Drive. */
@Serializable
internal data class ProgressFile(
    val version: Int = 1,
    val savedAt: Long = 0L,
    val entries: Map<String, ReadingProgress> = emptyMap(),
)

/**
 * [ProgressStore] sobre files/progress.json (atómico, con `.bak`), por clave de contenido (`md5:size`).
 *
 * - Se carga al construirse, de forma síncrona (el estado de cada número tiene que ser el bueno desde el primer
 *   fotograma). Si progress.json está roto, se usa el `.bak`.
 * - Cada cambio se publica al momento en [all] y se escribe a disco por detrás, agrupando ráfagas (un escritor
 *   con canal «conflated»). [flush] fuerza la escritura (llamarlo al pasar a segundo plano).
 * - [update] marca «leído» al llegar a la última página; un número ya leído sigue leído aunque se relea desde el
 *   principio (como Cover). Desmarcarlo ([setFinished] con false) lo deja en la página 0 («No leído»), pero la
 *   entrada se conserva con su fecha: así la copia de Drive no lo vuelve a marcar al mezclar.
 * - Copia en Drive: [backupToDrive] sube la mezcla de lo local y lo que ya hubiera en Drive (no pisa el progreso
 *   de otro dispositivo); [restoreFromDrive] mezcla lo de Drive en lo local. En ambos gana, por número, la entrada
 *   con `updatedAt` más reciente (empate: la local).
 */
class JsonProgressStore internal constructor(
    private val file: File,
    private val appData: AppDataStore?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : ProgressStore {

    private val mutex = Mutex()
    private val writeMutex = Mutex()
    private val dirty = AtomicBoolean(false)
    private val writeSignal = Channel<Unit>(Channel.CONFLATED)
    private val flow = MutableStateFlow(load())

    override val all: StateFlow<Map<String, ReadingProgress>> = flow.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in writeSignal) writeIfDirty()
        }
    }

    override fun get(contentKey: String): ReadingProgress? = flow.value[contentKey]

    override fun stateOf(book: Book): ReadState = flow.value[book.contentKey]?.state ?: ReadState.UNREAD

    override suspend fun update(book: Book, page: Int, pageCount: Int) {
        mutex.withLock {
            val key = book.contentKey
            val prev = flow.value[key]
            val safePage = page.coerceAtLeast(0)
            val reachedEnd = pageCount > 0 && safePage >= pageCount - 1
            val entry = ReadingProgress(
                contentKey = key,
                page = safePage,
                pageCount = pageCount,
                updatedAt = clock(),
                finished = prev?.finished == true || reachedEnd,
            )
            flow.value = flow.value + (key to entry)
        }
        scheduleWrite()
    }

    override suspend fun setFinished(book: Book, finished: Boolean, pageCount: Int?) {
        mutex.withLock {
            val now = clock()
            flow.value = flow.value + (book.contentKey to marked(book, finished, pageCount, now))
        }
        scheduleWrite()
    }

    override suspend fun setFinished(books: List<Book>, finished: Boolean) {
        if (books.isEmpty()) return
        mutex.withLock {
            val now = clock()
            val changes = books.associate { it.contentKey to marked(it, finished, null, now) }
            flow.value = flow.value + changes
        }
        scheduleWrite()
    }

    private fun marked(book: Book, finished: Boolean, pageCount: Int?, now: Long): ReadingProgress {
        val prev = flow.value[book.contentKey]
        val count = pageCount ?: prev?.pageCount ?: 0
        return if (finished) {
            ReadingProgress(book.contentKey, page = if (count > 0) count - 1 else prev?.page ?: 0, pageCount = count, updatedAt = now, finished = true)
        } else {
            ReadingProgress(book.contentKey, page = 0, pageCount = count, updatedAt = now, finished = false)
        }
    }

    override fun recent(limit: Int): List<ReadingProgress> {
        if (limit <= 0) return emptyList()
        return flow.value.values.asSequence()
            .filter { it.state != ReadState.UNREAD }
            .sortedByDescending { it.updatedAt }
            .take(limit)
            .toList()
    }

    /** Escribe ya lo pendiente (si hay algo). */
    suspend fun flush() {
        withContext(Dispatchers.IO) { writeIfDirty() }
    }

    // ------------------------------------------------------------------ copia en Drive

    override suspend fun backupToDrive(): Result<Unit> {
        val store = appData ?: return Result.failure(IllegalStateException(LibraryStrings.NO_APPDATA))
        return resultOf(LibraryStrings::backupFailed) {
            flush()
            val remote = store.get(DRIVE_NAME)?.let { bytes ->
                parseOrNull(bytes) ?: run {
                    log("progreso: la copia de Drive está dañada; se sustituye por la local")
                    null
                }
            }
            val merged = ProgressMerge.merge(flow.value, remote?.entries.orEmpty()).entries
            val payload = ProgressFile(savedAt = clock(), entries = merged)
            store.put(DRIVE_NAME, AtomicFiles.json.encodeToString(ProgressFile.serializer(), payload).toByteArray(Charsets.UTF_8))
            log("progreso: copia en Drive con ${merged.size} números")
        }
    }

    override suspend fun restoreFromDrive(): Result<Int> {
        val store = appData ?: return Result.failure(IllegalStateException(LibraryStrings.NO_APPDATA))
        return resultOf(LibraryStrings::restoreFailed) {
            val bytes = store.get(DRIVE_NAME) ?: return@resultOf 0
            val remote = parseOrNull(bytes) ?: throw IOException(LibraryStrings.BACKUP_CORRUPT)
            val changed = mutex.withLock {
                val result = ProgressMerge.merge(flow.value, remote.entries)
                if (result.changedLocally > 0) flow.value = result.entries
                result.changedLocally
            }
            if (changed > 0) {
                scheduleWrite()
                flush()
            }
            log("progreso: recuperados $changed números de Drive")
            changed
        }
    }

    private fun parseOrNull(bytes: ByteArray): ProgressFile? = try {
        AtomicFiles.json.decodeFromString(ProgressFile.serializer(), bytes.toString(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    private suspend inline fun <T> resultOf(crossinline describe: (String?) -> String, block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("progreso: ${e::class.java.simpleName}: ${e.message}")
        Result.failure(if (e is IOException && e.message == LibraryStrings.BACKUP_CORRUPT) e else IOException(describe(e.message), e))
    }

    // ------------------------------------------------------------------ disco

    private fun scheduleWrite() {
        dirty.set(true)
        writeSignal.trySend(Unit)
    }

    private suspend fun writeIfDirty() {
        writeMutex.withLock {
            if (!dirty.getAndSet(false)) return
            val payload = ProgressFile(savedAt = clock(), entries = flow.value)
            try {
                AtomicFiles.writeText(file, AtomicFiles.json.encodeToString(ProgressFile.serializer(), payload), keepBackup = true)
            } catch (e: IOException) {
                dirty.set(true)
                log("progreso: no se pudo guardar progress.json: ${e.message}")
            }
        }
    }

    private fun load(): Map<String, ReadingProgress> {
        AtomicFiles.cleanTemporaries(file)
        return AtomicFiles.readWithFallback(file, onProblem = log) { text ->
            AtomicFiles.json.decodeFromString(ProgressFile.serializer(), text)
        }?.entries ?: emptyMap()
    }

    internal companion object {
        const val FILE_NAME = "progress.json"
        const val DRIVE_NAME = "progress.json"
    }
}

/** Mezcla de progreso por número: gana el `updatedAt` más reciente; empate, el local. */
internal object ProgressMerge {
    class Result(val entries: Map<String, ReadingProgress>, val changedLocally: Int)

    fun merge(local: Map<String, ReadingProgress>, remote: Map<String, ReadingProgress>): Result {
        if (remote.isEmpty()) return Result(local, 0)
        val out = HashMap(local)
        var changed = 0
        for ((key, theirs) in remote) {
            val fixed = if (theirs.contentKey == key) theirs else theirs.copy(contentKey = key)
            val mine = local[key]
            if (mine == null || fixed.updatedAt > mine.updatedAt) {
                if (mine != fixed) changed++
                out[key] = fixed
            }
        }
        return Result(out, changed)
    }
}
