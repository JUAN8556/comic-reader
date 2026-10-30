package mx.jano.comics.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mx.jano.comics.auth.AuthFailedException
import mx.jano.comics.auth.NeedsConsentException
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.library.Book
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.settings.SettingsStore
import java.io.File
import java.io.IOException

/** Lo que va ya bajado de la tanda en curso (el total sale de sumar la cola). */
@Serializable
data class PinBatch(val done: Int = 0, val bytesDone: Long = 0)

/** Lo que se guarda en files/pins.json: los libros enteros, para poder bajarlos sin el índice cargado. */
@Serializable
data class PinSnapshot(
    val version: Int = 1,
    val pinned: List<Book> = emptyList(),
    /** bookIds fijados que aún no están completos en disco, en orden de descarga. */
    val queue: List<String> = emptyList(),
    val paused: Boolean = false,
    val batch: PinBatch = PinBatch(),
    val lastError: String? = null,
)

/** Quien programa la descarga en segundo plano (en Android, JobScheduler UIDT). */
interface PinScheduler {
    /** Programa la descarga; false si el sistema no lo deja ahora (p. ej. la app no está visible). */
    fun schedule(estimatedBytes: Long, unmeteredOnly: Boolean): Boolean
    fun cancel()
    /** Programada o corriendo. */
    fun isScheduled(): Boolean
}

/** Notificación fuera de un trabajo en marcha (el trabajo pinta la suya). */
interface PinNotifier {
    fun showPaused(progress: PinProgress?)
    fun showNeedsApp()
    fun clear()

    object None : PinNotifier {
        override fun showPaused(progress: PinProgress?) = Unit
        override fun showNeedsApp() = Unit
        override fun clear() = Unit
    }
}

fun interface PinRunListener {
    fun onProgress(progress: PinProgress, bytesPerSecond: Long)

    companion object {
        val NONE = PinRunListener { _, _ -> }
    }
}

sealed interface PinRunResult {
    data class Completed(val done: Int) : PinRunResult
    data object Paused : PinRunResult
    /** Fallo de red o de Drive pasajero: el trabajo se reprograma solo. */
    data class Retry(val why: String) : PinRunResult
    data class NoSpace(val message: String) : PinRunResult
    /** Hace falta la app en primer plano (consentimiento, permiso, sesión). */
    data class NeedsApp(val why: String) : PinRunResult
    data class Failed(val message: String) : PinRunResult
}

/**
 * [PinManager]: fijar = apuntar en pins.json (atómico) + cola de lo que falta + descarga en segundo plano.
 * La descarga la corre [runQueue] dentro de un trabajo UIDT ([PinJobService]); si el sistema no deja programarlo y
 * la app está visible, se corre aquí mismo mientras viva el proceso. Al arrancar, la cola se concilia con el disco
 * (lo fijado que no está completo vuelve a la cola) y, si no está en pausa, se reprograma con la app visible.
 */
class PinController(
    private val cache: CacheManager,
    private val store: JsonStateStore<PinSnapshot>,
    private val scheduler: PinScheduler,
    private val settings: SettingsStore?,
    private val library: LibraryRepository?,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val notifier: PinNotifier = PinNotifier.None,
    private val isAppVisible: () -> Boolean = { true },
    /** ¿Hay red adecuada para bajar ya, sin trabajo programado? (solo para el plan B en el proceso). */
    private val canDownloadNow: (unmeteredOnly: Boolean) -> Boolean = { true },
    private val log: (String) -> Unit = {},
) : PinManager {

    private val mutex = Mutex()
    private val runLock = Mutex()
    private val kickLock = Any()
    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var snap: PinSnapshot = store.load() ?: PinSnapshot()

    @Volatile
    private var byId: Map<String, Book> = snap.pinned.associateBy { it.id }

    @Volatile
    private var currentId: String? = null

    @Volatile
    private var currentBytes: Long = 0

    @Volatile
    private var currentFetch: Job? = null

    @Volatile
    private var inProcessRun: Job? = null

    private val _pins = MutableStateFlow(byId.keys.toSet())
    override val pins: StateFlow<Set<String>> = _pins.asStateFlow()

    private val _progress = MutableStateFlow<PinProgress?>(null)
    override val progress: StateFlow<PinProgress?> = _progress.asStateFlow()

    private val _paused = MutableStateFlow(snap.paused)
    /** Extra del contrato: si la cola está en pausa (PinProgress no lo dice). */
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    private val _lastError = MutableStateFlow(snap.lastError)
    /** Extra del contrato: el último problema (sin espacio, archivo que ya no está en Drive…). */
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val disk: DiskCacheManager? get() = cache as? DiskCacheManager

    init {
        publish()
        scope.launch {
            try {
                disk?.awaitReady()
                reconcileWithDisk()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("pins: la conciliación falló: ${e.message}")
            } finally {
                ready.complete(Unit)
            }
            if (isAppVisible()) kick()
        }
    }

    suspend fun awaitReady() = ready.await()

    override fun isPinned(book: Book): Boolean = book.id in _pins.value

    override suspend fun pin(books: List<Book>): Long {
        awaitReady()
        val unique = books.distinctBy { it.id }
        if (unique.isEmpty()) return 0
        val remaining = mutex.withLock {
            val pinned = LinkedHashMap<String, Book>()
            snap.pinned.forEach { pinned[it.id] = it }
            unique.forEach { pinned[it.id] = it } // datos frescos del índice
            val toFetch = unique.filter { cache.localFile(it) == null }
            val queue = snap.queue.toMutableList()
            toFetch.forEach { if (it.id !in queue) queue += it.id }
            val batch = if (snap.queue.isEmpty()) PinBatch() else snap.batch
            saveLocked(snap.copy(pinned = pinned.values.toList(), queue = queue, paused = false, batch = batch, lastError = null))
            toFetch.sumOf { remainingBytes(it) }
        }
        syncCachePins()
        publish()
        kick()
        return remaining
    }

    override suspend fun unpin(books: List<Book>) {
        awaitReady()
        val ids = books.map { it.id }.toSet()
        if (ids.isEmpty()) return
        val emptied = mutex.withLock {
            saveLocked(snap.copy(pinned = snap.pinned.filter { it.id !in ids }, queue = snap.queue.filter { it !in ids }))
            snap.queue.isEmpty()
        }
        val fetch = if (currentId in ids) currentFetch else null
        fetch?.cancel()
        syncCachePins()
        publish()
        if (emptied) {
            stopRunners()
            notifier.clear()
        }
        // Lo que se dejó a medias de esos libros ya no tiene dueño: fuera (si un lector lo espera, se queda).
        fetch?.join()
        disk?.discardPartials(ids)
    }

    override fun pause() {
        scope.launch {
            awaitReady()
            mutex.withLock { saveLocked(snap.copy(paused = true)) }
            publish()
            stopRunners()
            if (snap.queue.isNotEmpty()) notifier.showPaused(_progress.value)
        }
    }

    override fun resume() {
        scope.launch {
            awaitReady()
            mutex.withLock { saveLocked(snap.copy(paused = false, lastError = null)) }
            publish()
            kick()
        }
    }

    /** Cancela lo que falta: esos libros dejan de estar fijados; los ya completos siguen fijados. */
    override fun cancel() {
        scope.launch {
            awaitReady()
            val pending = mutex.withLock {
                val pending = snap.queue.toSet()
                saveLocked(snap.copy(pinned = snap.pinned.filter { it.id !in pending }, queue = emptyList(), paused = false, batch = PinBatch()))
                pending
            }
            syncCachePins()
            publish()
            stopRunners()
            notifier.clear()
            // Los .part de lo cancelado ya no tienen dueño: si se quedaran, contarían contra el tope para siempre.
            disk?.discardPartials(pending)
        }
    }

    /** La app pasó a primer plano: si hay cola sin trabajo programado (lo mató el sistema, reinicio…), se programa. */
    fun onAppVisible() {
        scope.launch {
            awaitReady()
            reconcileWithDisk()
            kick()
        }
    }

    /** El usuario lo paró desde el sistema (Administrador de tareas): queda en pausa hasta que lo reanude. */
    fun markStoppedByUser() {
        scope.launch {
            mutex.withLock { saveLocked(snap.copy(paused = true)) }
            publish()
        }
    }

    val queueSize: Int get() = snap.queue.size
    val isPausedNow: Boolean get() = snap.paused
    val unmeteredOnly: Boolean get() = settings?.app?.value?.wifiOnlyPrefetch ?: true

    /** Bytes que faltan de toda la cola (estimación para JobInfo). */
    fun remainingQueueBytes(): Long = snap.queue.sumOf { id -> byId[id]?.let { remainingBytes(it) } ?: 0L }

    /**
     * Para el trabajo UIDT: para el plan B si estaba corriendo y corre la cola. Si mientras terminaba se fijó algo
     * más, sigue con ello (el pin() de entonces vio el trabajo «programado» y no lo reprogramó).
     */
    suspend fun runFromJob(listener: PinRunListener): PinRunResult {
        inProcessRun?.cancelAndJoin()
        inProcessRun = null
        var result: PinRunResult
        do {
            result = runQueue(listener)
        } while (result is PinRunResult.Completed && snap.queue.isNotEmpty() && !snap.paused)
        return result
    }

    /** El trabajo terminó: si quedó algo en la cola (fijado en el último instante), se reprograma. */
    fun onJobEnded() {
        scope.launch {
            delay(JOB_END_SETTLE_MS) // que el sistema dé el trabajo por terminado antes de mirar si sigue programado
            if (snap.queue.isNotEmpty() && !snap.paused && isAppVisible()) kick()
        }
    }

    /** Baja la cola en orden hasta vaciarla, pausarse o topar con un fallo. Un solo corredor a la vez. */
    suspend fun runQueue(listener: PinRunListener): PinRunResult = runLock.withLock {
        awaitReady()
        var result: PinRunResult? = null
        while (result == null) {
            currentCoroutineContext().ensureActive()
            result = step(listener)
        }
        result
    }

    /** Un libro de la cola. null = seguir con el siguiente. */
    private suspend fun step(listener: PinRunListener): PinRunResult? {
        val s = snap
        if (s.paused) return PinRunResult.Paused
        val id = s.queue.firstOrNull() ?: return PinRunResult.Completed(s.batch.done)
        val stored = byId[id]
        if (stored == null) {
            dropFromQueue(id, error = null)
            return null
        }
        val book = freshBook(stored)
        if (cache.localFile(book) != null) {
            markDone(book)
            return null
        }
        currentId = id
        currentBytes = disk?.partialBytes(id) ?: 0L
        publish()
        _progress.value?.let { listener.onProgress(it, 0) }
        val outcome = try {
            fetchCurrent(book, listener)
        } finally {
            currentId = null
            currentBytes = 0
        }
        return when (outcome) {
            is FetchOutcome.Done -> {
                markDone(book)
                null
            }
            FetchOutcome.Skipped -> {
                publish() // desfijado a mitad: se sigue con el siguiente
                null
            }
            is FetchOutcome.Failed -> classify(book, outcome.error)
        }
    }

    private sealed interface FetchOutcome {
        data class Done(val file: File) : FetchOutcome
        data object Skipped : FetchOutcome
        data class Failed(val error: Exception) : FetchOutcome
    }

    private suspend fun fetchCurrent(book: Book, listener: PinRunListener): FetchOutcome = supervisorScope {
        val fetch = async {
            val onProgress: (DownloadProgress) -> Unit = { p ->
                currentBytes = p.bytes
                publish()
                _progress.value?.let { listener.onProgress(it, p.bytesPerSecond) }
            }
            disk?.ensureLocalFor(book, DownloadPurpose.PIN, onProgress) ?: cache.ensureLocal(book, onProgress)
        }
        currentFetch = fetch
        try {
            FetchOutcome.Done(fetch.await())
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // si paran al corredor, se propaga
            FetchOutcome.Skipped
        } catch (e: Exception) {
            FetchOutcome.Failed(e)
        } finally {
            currentFetch = null
        }
    }

    /** null = seguir con el siguiente; si no, el resultado con el que se para. */
    private suspend fun classify(book: Book, error: Exception): PinRunResult? {
        log("pins: ${book.id} falló: ${error::class.simpleName}: ${error.message}")
        return when (error) {
            is InsufficientSpaceException -> {
                setError(error.message ?: CacheStrings.ERROR_NO_SPACE)
                PinRunResult.NoSpace(error.message ?: CacheStrings.ERROR_NO_SPACE)
            }
            is DownloadCancelledException -> {
                // Otro canceló esta descarga (quitar descarga, cancelar transferencia): se aparta de la cola sin
                // desfijar; al volver a la app la conciliación la devuelve. Así no se repite en bucle.
                dropFromQueue(book.id, error = null)
                null
            }
            is CorruptDownloadException -> {
                // Ya se reintentó desde cero: se aparta de la cola (sigue fijado; al volver a la app se reintenta).
                dropFromQueue(book.id, error = error.message)
                null
            }
            is DriveHttpException -> when {
                error.code == 404 -> {
                    // Ya no está en Drive: deja de estar fijado.
                    mutex.withLock { saveLocked(snap.copy(pinned = snap.pinned.filter { it.id != book.id }, queue = snap.queue - book.id, lastError = "${CacheStrings.ERROR_NOT_IN_DRIVE}: ${book.name}")) }
                    syncCachePins()
                    publish()
                    null
                }
                error.code == 429 || error.code >= 500 || error.reason == "rateLimitExceeded" || error.reason == "userRateLimitExceeded" ->
                    PinRunResult.Retry(error.message ?: "HTTP ${error.code}")
                error.code == 401 || error.code == 403 -> PinRunResult.NeedsApp(error.message ?: "HTTP ${error.code}")
                else -> {
                    dropFromQueue(book.id, error = error.message)
                    null
                }
            }
            is NeedsConsentException, is AuthFailedException -> PinRunResult.NeedsApp(error.message ?: "auth")
            is IOException -> PinRunResult.Retry(error.message ?: error::class.java.simpleName)
            else -> PinRunResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    // --- Interno ---

    private fun freshBook(stored: Book): Book = try {
        library?.book(stored.id) ?: stored
    } catch (_: Throwable) {
        stored
    }

    private fun remainingBytes(book: Book): Long =
        disk?.remainingBytes(book) ?: if (cache.localFile(book) != null) 0L else book.size

    private suspend fun markDone(book: Book) {
        mutex.withLock {
            if (book.id !in snap.queue) return@withLock
            saveLocked(snap.copy(queue = snap.queue - book.id, batch = PinBatch(snap.batch.done + 1, snap.batch.bytesDone + book.size)))
        }
        syncCachePins()
        publish()
    }

    private suspend fun dropFromQueue(id: String, error: String?) {
        mutex.withLock { saveLocked(snap.copy(queue = snap.queue - id, lastError = error ?: snap.lastError)) }
        publish()
    }

    private suspend fun setError(message: String) {
        mutex.withLock { saveLocked(snap.copy(lastError = message)) }
        publish()
    }

    /** Lo fijado que no está completo vuelve a la cola (al final, en el orden en que se fijó). */
    private suspend fun reconcileWithDisk() {
        mutex.withLock {
            val missing = snap.pinned.filter { cache.localFile(it) == null }.map { it.id }
            val missingSet = missing.toSet()
            val queue = snap.queue.filter { it in missingSet } + missing.filter { it !in snap.queue }
            if (queue != snap.queue) {
                log("pins: cola conciliada con el disco: ${snap.queue.size} → ${queue.size}")
                saveLocked(snap.copy(queue = queue, batch = if (queue.isEmpty()) PinBatch() else snap.batch))
            }
        }
        syncCachePins()
        publish()
    }

    /** Programa el trabajo si hace falta; si el sistema no deja y la app está visible, plan B en el proceso. */
    private fun kick() {
        synchronized(kickLock) { kickLocked() }
    }

    private fun kickLocked() {
        val s = snap
        if (s.paused || s.queue.isEmpty()) return
        if (inProcessRun?.isActive == true) return
        val scheduled = try {
            scheduler.isScheduled()
        } catch (_: Exception) {
            false
        }
        if (scheduled) return
        val unmetered = unmeteredOnly
        val ok = try {
            scheduler.schedule(remainingQueueBytes(), unmetered)
        } catch (e: Exception) {
            log("pins: no se pudo programar el trabajo: ${e.message}")
            false
        }
        if (ok) return
        if (!isAppVisible()) {
            notifier.showNeedsApp()
            return
        }
        if (!canDownloadNow(unmetered)) return // ya se reintentará al volver a primer plano
        log("pins: sin trabajo UIDT; bajo la cola dentro del proceso")
        inProcessRun = scope.launch {
            try {
                runQueue(PinRunListener.NONE)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("pins: el plan B falló: ${e.message}")
            }
        }
    }

    private suspend fun stopRunners() {
        try {
            scheduler.cancel()
        } catch (e: Exception) {
            log("pins: no se pudo cancelar el trabajo: ${e.message}")
        }
        inProcessRun?.cancelAndJoin()
        inProcessRun = null
        currentFetch?.let {
            it.cancel()
            it.join() // que la caché sepa ya que nadie espera esa descarga
        }
    }

    private suspend fun syncCachePins() {
        disk?.setPinnedIds(snap.pinned.map { it.id }.toSet())
    }

    /** Bajo [mutex]. Primero en memoria (lo ven todos al momento), luego a disco fuera del hilo principal. */
    private suspend fun saveLocked(next: PinSnapshot) {
        snap = next
        byId = next.pinned.associateBy { it.id }
        withContext(io + NonCancellable) {
            try {
                store.save(next)
            } catch (e: Exception) {
                log("pins: no se pudo guardar pins.json: ${e.message}")
            }
        }
    }

    private fun publish() {
        val s = snap
        _pins.value = byId.keys.toSet()
        _paused.value = s.paused
        _lastError.value = s.lastError
        _progress.value = progressOf(s)
    }

    private companion object {
        const val JOB_END_SETTLE_MS = 1_500L
    }

    private fun progressOf(s: PinSnapshot): PinProgress? {
        if (s.queue.isEmpty()) return null
        val queuedBytes = s.queue.sumOf { byId[it]?.size ?: 0L }
        val current = currentId?.takeIf { it in s.queue }
        return PinProgress(
            total = s.batch.done + s.queue.size,
            done = s.batch.done,
            bytesDone = s.batch.bytesDone + (if (current != null) currentBytes else 0L),
            bytesTotal = s.batch.bytesDone + queuedBytes,
            current = current?.let { byId[it]?.name },
        )
    }
}
