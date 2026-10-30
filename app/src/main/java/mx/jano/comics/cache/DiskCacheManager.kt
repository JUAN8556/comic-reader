package mx.jano.comics.cache

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mx.jano.comics.archive.ArchiveKind
import mx.jano.comics.archive.ArchiveSniffer
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.drive.DriveRestApi
import mx.jano.comics.drive.PartialVersion
import mx.jano.comics.library.Book
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.library.ReadState
import mx.jano.comics.settings.SettingsStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/** Lo que se guarda en files/cache.json. */
@Serializable
data class CacheSnapshot(
    val version: Int = 1,
    val entries: List<CachedBook> = emptyList(),
    val pinned: Set<String> = emptySet(),
)

/**
 * [CacheManager] sobre una carpeta privada de la app (getExternalFilesDir("books"): sin permisos de almacenamiento).
 *
 * Disco:
 * - `<bookId>.<ext>`: libro completo y verificado; la extensión sale de los bytes (cbz, cbr, cb7, pdf) o, si no se
 *   reconoce, del nombre original.
 * - `<bookId>.part` + `<bookId>.part.meta`: descarga a medias y la huella de su versión en Drive (la escribe
 *   [DriveRestApi.download] ANTES del primer byte). Un .part sin .meta, o un .meta sin .part, se borra al arrancar.
 * - Las páginas extraídas viven aparte, en cacheDir/pages/<bookId>, y se borran con el libro.
 *
 * Una descarga termina así: verificar tamaño y md5 contra la huella (la versión que de verdad se bajó) → renombrar
 * de forma atómica a `<bookId>.<ext>` → borrar la huella → apuntar en cache.json (atómico). Si se corta entre el
 * renombrado y cache.json, al arrancar se «adopta» el fichero completo (o se borra si [adoptOrphans] es false).
 *
 * Varios que piden el mismo libro comparten una sola descarga; si todos se van, la descarga se cancela (queda el
 * .part para reanudar con Range).
 */
class DiskCacheManager(
    private val booksDir: File,
    private val pagesRoot: File,
    private val drive: DriveApi,
    internal val settings: SettingsStore,
    private val library: LibraryRepository?,
    private val store: JsonStateStore<CacheSnapshot>,
    private val storage: StorageStats,
    private val clock: Clock = Clock.SYSTEM,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val adoptOrphans: Boolean = true,
    private val log: (String) -> Unit = {},
    progress: ProgressStore? = null,
    /** Cuántos bytes es «1 GB» de Ajustes (las pruebas lo encogen para no escribir gigas). */
    private val bytesPerGb: Long = GB,
    /** Para abrir un libro se acepta pasar del tope, pero nunca dejar la tablet con menos de esto libre. */
    private val readFloorBytes: Long = READ_FLOOR_BYTES,
    /** Si no se pasó [progress], de dónde sacarlo cuando haga falta (en la app, del AppGraph). */
    private val progressLookup: () -> ProgressStore? = { null },
) : CacheManager {

    /** De dónde sale «terminado / no empezado / leyendo» para la prioridad del desalojo. Sin él: LRU puro. */
    @Volatile
    var progress: ProgressStore? = progress

    private val mutex = Mutex()

    @Volatile
    private var entries: Map<String, CachedBook> = emptyMap()

    @Volatile
    private var pinnedIds: Set<String> = emptySet()

    @Volatile
    private var protectIds: Set<String> = emptySet()

    private val flightsLock = Any()
    private val flights = HashMap<String, Flight>()

    private val ready = CompletableDeferred<Unit>()

    private val _state: MutableStateFlow<CacheState>
    override val state: StateFlow<CacheState>

    private val _downloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    override val downloads: StateFlow<Map<String, DownloadProgress>> = _downloads.asStateFlow()

    init {
        booksDir.mkdirs()
        pagesRoot.mkdirs()
        val loaded = store.load()
        val snapshot = loaded ?: CacheSnapshot()
        entries = snapshot.entries.associateBy { it.bookId }
        pinnedIds = snapshot.pinned
        _state = MutableStateFlow(buildState())
        state = _state.asStateFlow()
        scope.launch(io) {
            try {
                reconcile()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log("cache: la conciliación con el disco falló: ${e.message}")
            } finally {
                ready.complete(Unit)
            }
            // Sin cache.json no se sabe aún qué está fijado: el primer desalojo espera a que lo diga el PinManager
            // (o a la siguiente descarga / enforceLimits).
            if (loaded != null) enforceLimitsQuietly()
            // Si cambian el tope o el margen en Ajustes, se aplican al momento.
            settings.app
                .map { it.cacheLimitGb to it.minFreeGb }
                .distinctUntilChanged()
                .drop(1)
                .collect {
                    publishState()
                    enforceLimitsQuietly()
                }
        }
    }

    /** Espera a que termine la conciliación del arranque. */
    suspend fun awaitReady() = ready.await()

    /** Vuelve a medir el espacio libre del volumen (p. ej. al abrir Ajustes); el resto del estado ya va en vivo. */
    fun refresh() = publishState()

    // --- Consultas (sin esperar, sin disco salvo localFile) ---

    override fun availability(book: Book): Availability {
        if (isDownloading(book.id)) return Availability.DOWNLOADING
        val entry = entries[book.id] ?: return Availability.CLOUD
        if (!matches(entry, book)) return Availability.CLOUD
        return if (isPinned(book.id)) Availability.PINNED else Availability.LOCAL
    }

    override fun localFile(book: Book): File? {
        val entry = entries[book.id] ?: return null
        if (!matches(entry, book)) return null
        return File(entry.file).takeIf { it.isFile }
    }

    override fun pageCacheDir(book: Book): File = File(pagesRoot, safeName(book.id)).also { it.mkdirs() }

    /** Bytes que ya hay en disco de una descarga a medias (para estimar lo que falta). */
    fun partialBytes(bookId: String): Long = partFile(bookId).let { if (it.isFile) it.length() else 0L }

    /** Lo que falta por bajar de [book]: 0 si ya está completo. */
    fun remainingBytes(book: Book): Long =
        if (localFile(book) != null) 0L else (book.size - partialBytes(book.id)).coerceAtLeast(0L)

    fun isDownloading(bookId: String): Boolean = synchronized(flightsLock) { flights.containsKey(bookId) }

    /** Para qué se está bajando ahora [bookId] (la primera petición manda), o null. */
    fun downloadPurpose(bookId: String): DownloadPurpose? = synchronized(flightsLock) { flights[bookId]?.purpose }

    private fun isPinned(bookId: String): Boolean = bookId in pinnedIds || entries[bookId]?.pinned == true

    // --- Descarga ---

    override suspend fun ensureLocal(book: Book, onProgress: (DownloadProgress) -> Unit): File =
        ensureLocalFor(book, DownloadPurpose.READ, onProgress)

    /**
     * Como [ensureLocal], diciendo para qué se baja (decide cuánto se puede apretar el disco). Solo [DownloadPurpose.READ]
     * marca lastOpened si el libro ya estaba. Si hay una copia de otra versión y no se puede bajar la nueva (sin red,
     * sin sitio, borrado en Drive), para leer se devuelve la vieja.
     */
    suspend fun ensureLocalFor(
        book: Book,
        purpose: DownloadPurpose,
        onProgress: (DownloadProgress) -> Unit = {},
    ): File {
        awaitReady()
        val existing = entries[book.id]
        if (existing != null && matches(existing, book)) {
            val file = File(existing.file)
            if (file.isFile) {
                touch(book, existing, markOpened = purpose == DownloadPurpose.READ)
                return file
            }
        }
        val stale = existing?.takeIf { File(it.file).isFile }
        var attempt = 0
        while (true) {
            attempt++
            try {
                return awaitFlight(book, purpose, onProgress)
            } catch (e: InsufficientSpaceException) {
                // La descarga compartida era más estricta (un N+1) y quien llama ahora es más laxo: se reintenta.
                if (attempt == 1 && e.purpose != purpose && purpose != DownloadPurpose.PREFETCH) continue
                return staleOr(stale, purpose, e)
            } catch (e: DownloadCancelledException) {
                throw e
            } catch (e: IOException) {
                return staleOr(stale, purpose, e)
            } catch (e: DriveHttpException) {
                return staleOr(stale, purpose, e)
            }
        }
    }

    private suspend fun staleOr(stale: CachedBook?, purpose: DownloadPurpose, error: Exception): File {
        if (stale == null || purpose != DownloadPurpose.READ) throw error
        log("cache: no se pudo bajar la versión nueva de ${stale.bookId} (${error.message}); abro la copia que hay")
        val file = File(stale.file)
        mutex.withLock {
            val current = entries[stale.bookId] ?: return@withLock
            entries = entries + (stale.bookId to current.copy(lastOpened = clock.now()))
            persistLocked()
        }
        publishState()
        return file
    }

    private suspend fun touch(book: Book, entry: CachedBook, markOpened: Boolean) {
        val upgradeKey = entry.contentKey != book.contentKey // adoptado sin índice: ya sabemos su clave
        if (!markOpened && !upgradeKey) return
        mutex.withLock {
            val current = entries[book.id] ?: return@withLock
            val updated = current.copy(
                contentKey = if (upgradeKey) book.contentKey else current.contentKey,
                lastOpened = if (markOpened) clock.now() else current.lastOpened,
            )
            entries = entries + (book.id to updated)
            persistLocked()
        }
        publishState()
    }

    private inner class Flight(val bookId: String, val purpose: DownloadPurpose) {
        lateinit var job: Deferred<File>
        val listeners = CopyOnWriteArrayList<(DownloadProgress) -> Unit>()
        var waiters = 0 // bajo flightsLock
    }

    private suspend fun awaitFlight(book: Book, purpose: DownloadPurpose, onProgress: (DownloadProgress) -> Unit): File {
        val flight = synchronized(flightsLock) {
            val f = flights[book.id] ?: Flight(book.id, purpose).also { created ->
                created.job = scope.async(io, start = CoroutineStart.LAZY) { download(book, purpose, created) }
                flights[book.id] = created
                created.job.invokeOnCompletion {
                    synchronized(flightsLock) { if (flights[book.id] === created) flights.remove(book.id) }
                    _downloads.update { it - book.id }
                    publishState()
                }
            }
            f.waiters++
            f.listeners += onProgress
            f
        }
        flight.job.start()
        try {
            return flight.job.await()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // si nos cancelaron a nosotros, sigue siendo una cancelación
            throw DownloadCancelledException(book.id)
        } finally {
            val abandoned = synchronized(flightsLock) {
                flight.waiters--
                flight.listeners -= onProgress
                flight.waiters == 0 && flight.job.isActive
            }
            if (abandoned) flight.job.cancel()
        }
    }

    private suspend fun download(book: Book, purpose: DownloadPurpose, flight: Flight): File {
        val part = partFile(book.id)
        var attempt = 0
        while (true) {
            attempt++
            val already = if (part.isFile) part.length() else 0L
            makeRoom(book.id, (book.size - already).coerceAtLeast(0L), purpose)
            val meter = SpeedMeter(clock)
            var lastEmit = Long.MIN_VALUE
            val knownTotal = book.size.takeIf { it > 0 }
            emitProgress(flight, DownloadProgress(book.id, already, knownTotal, 0))
            drive.download(book.id, part, resume = true) { bytes, total ->
                val speed = meter.add(bytes)
                val now = clock.now()
                val finished = total != null && bytes >= total
                if (finished || now - lastEmit >= PROGRESS_INTERVAL_MS) {
                    lastEmit = now
                    emitProgress(flight, DownloadProgress(book.id, bytes, total ?: knownTotal, speed))
                }
            }
            val stamp = DriveRestApi.readPartialVersion(DriveRestApi.partialVersionFile(part))
            val problem = verify(part, book, stamp)
            if (problem == null) return install(book, part, stamp)
            log("cache: ${book.id} no pasa la verificación ($problem), intento $attempt")
            deletePartial(book.id)
            if (attempt >= MAX_VERIFY_ATTEMPTS) throw CorruptDownloadException(book.id, problem)
        }
    }

    private fun emitProgress(flight: Flight, progress: DownloadProgress) {
        _downloads.update { it + (progress.bookId to progress) }
        for (listener in flight.listeners) {
            try {
                listener(progress)
            } catch (e: Exception) {
                log("cache: un oyente de progreso falló: ${e.message}")
            }
        }
    }

    /** Tamaño y md5 contra la versión que de verdad se bajó (la huella); si no hay huella, contra el índice. */
    private suspend fun verify(part: File, book: Book, stamp: PartialVersion?): String? {
        if (!part.isFile) return "no hay fichero"
        val expectedSize = stamp?.size ?: book.size.takeIf { it > 0 }
        if (expectedSize != null && part.length() != expectedSize) return "tamaño ${part.length()} en vez de $expectedSize"
        val expectedMd5 = (stamp?.md5Checksum ?: book.md5)?.lowercase()
        if (expectedMd5 != null) {
            val actual = md5Hex(part)
            if (actual != expectedMd5) return "md5 $actual en vez de $expectedMd5"
        }
        return null
    }

    private suspend fun install(book: Book, part: File, stamp: PartialVersion?): File = withContext(io) {
        val kind = try {
            ArchiveSniffer.sniff(part)
        } catch (_: Exception) {
            ArchiveKind.UNKNOWN
        }
        val dest = File(booksDir, "${safeName(book.id)}.${extensionFor(kind, book.name)}")
        val bytes = part.length()
        val key = contentKeyOf(stamp?.md5Checksum ?: book.md5, book.id, bytes)
        if (key != book.contentKey) log("cache: ${book.id} cambió en Drive desde el último escaneo ($key)")
        // Verificado: se instala entero aunque cancelen ahora (si no, quedaría a medias entre disco y cache.json).
        withContext(NonCancellable) {
            mutex.withLock {
                val old = entries[book.id]
                if (old != null && File(old.file).absolutePath != dest.absolutePath) File(old.file).delete()
                moveReplacing(part, dest)
                DriveRestApi.partialVersionFile(part).delete()
                // Páginas de una versión anterior (o de un desalojo a medias): ya no valen.
                File(pagesRoot, safeName(book.id)).deleteRecursively()
                entries = entries + (book.id to CachedBook(
                    bookId = book.id,
                    contentKey = key,
                    file = dest.absolutePath,
                    bytes = bytes,
                    lastOpened = clock.now(),
                    pinned = book.id in pinnedIds,
                ))
                persistLocked()
            }
        }
        publishState()
        dest
    }

    override fun cancelDownload(bookId: String) {
        val flight = synchronized(flightsLock) { flights[bookId] } ?: return
        flight.job.cancel()
    }

    private suspend fun cancelFlightAndWait(bookId: String) {
        val flight = synchronized(flightsLock) { flights[bookId] } ?: return
        flight.job.cancelAndJoin()
    }

    // --- Espacio ---

    /** Desaloja lo necesario para meter [incoming] bytes de [bookId]; si no cabe según [purpose], lanza. */
    private suspend fun makeRoom(bookId: String, incoming: Long, purpose: DownloadPurpose) {
        val plan = mutex.withLock {
            val plan = planLocked(incoming, extraProtect = setOf(bookId))
            if (!EvictionPolicy.allows(plan, purpose, readFloorBytes)) {
                val app = settings.app.value
                throw InsufficientSpaceException(
                    bookId, purpose, incoming,
                    storage.freeBytes(booksDir), app.minFreeGb.toLong() * bytesPerGb,
                )
            }
            evictLocked(plan.evict)
            plan
        }
        if (plan.evict.isNotEmpty()) publishState()
    }

    override suspend fun enforceLimits(protect: Set<String>) {
        protectIds = protect
        awaitReady()
        enforceLimitsQuietly()
    }

    private suspend fun enforceLimitsQuietly() {
        val evicted = mutex.withLock {
            val plan = planLocked(incoming = 0, extraProtect = emptySet())
            if (!plan.limitOk || !plan.freeOk) {
                log("cache: tras desalojar ${plan.evict.size} sigue sin caber (uso ${plan.usedAfter}, libre ${plan.freeAfter})")
            }
            evictLocked(plan.evict)
            plan.evict
        }
        if (evicted.isNotEmpty()) log("cache: desalojados ${evicted.size} libros (${evicted.sumOf { it.bytes }} bytes)")
        publishState()
    }

    private fun planLocked(incoming: Long, extraProtect: Set<String>): EvictionPlan {
        val app = settings.app.value
        val inFlight = synchronized(flightsLock) { flights.keys.toSet() }
        val input = EvictionInput(
            entries = entries.values.toList(),
            pinned = pinnedIds,
            protect = protectIds + extraProtect + inFlight,
            partialBytes = partialBytesOnDisk(),
            limitBytes = app.cacheLimitGb.toLong() * bytesPerGb,
            freeBytes = storage.freeBytes(booksDir),
            minFreeBytes = app.minFreeGb.toLong() * bytesPerGb,
            incomingBytes = incoming,
        )
        return EvictionPolicy.plan(input, ::readStateOf)
    }

    private fun readStateOf(entry: CachedBook): ReadState? {
        val store = progress ?: lookUpProgress() ?: return null
        return try {
            store.get(entry.contentKey)?.state ?: library?.book(entry.bookId)?.let { store.stateOf(it) }
        } catch (_: Throwable) {
            null // el progreso aún no está (o falla): sin prioridad, LRU puro
        }
    }

    private fun lookUpProgress(): ProgressStore? = try {
        progressLookup()?.also { progress = it }
    } catch (_: Throwable) {
        null
    }

    /** Bajo [mutex]. */
    private suspend fun evictLocked(victims: List<CachedBook>) {
        if (victims.isEmpty()) return
        withContext(io) {
            for (victim in victims) {
                File(victim.file).delete()
                File(pagesRoot, safeName(victim.bookId)).deleteRecursively()
            }
        }
        entries = entries - victims.map { it.bookId }.toSet()
        persistLocked()
    }

    override suspend fun evict(book: Book) {
        awaitReady()
        cancelFlightAndWait(book.id)
        mutex.withLock {
            val entry = entries[book.id]
            withContext(io) {
                entry?.let { File(it.file).delete() }
                deletePartial(book.id)
                File(pagesRoot, safeName(book.id)).deleteRecursively()
            }
            if (entry != null) {
                entries = entries - book.id
                persistLocked()
            }
        }
        publishState()
    }

    /** Vacía la caché salvo lo fijado (para borrar un fijado, primero se desfija). */
    override suspend fun clearAll() {
        awaitReady()
        val keep = pinnedIds + entries.values.filter { it.pinned }.map { it.bookId }
        val toCancel = synchronized(flightsLock) { flights.keys.filter { it !in keep } }
        toCancel.forEach { cancelFlightAndWait(it) }
        mutex.withLock {
            val victims = entries.values.filter { it.bookId !in keep }
            withContext(io) {
                victims.forEach { File(it.file).delete() }
                booksDir.listFiles().orEmpty()
                    .filter { isPartialName(it.name) && idOf(it.name) !in keep }
                    .forEach { it.delete() }
                pagesRoot.listFiles().orEmpty().filter { it.name !in keep }.forEach { it.deleteRecursively() }
            }
            entries = entries - victims.map { it.bookId }.toSet()
            persistLocked()
        }
        publishState()
    }

    /** Los fijados según el PinManager: no se desalojan y su disponibilidad es PINNED. */
    suspend fun setPinnedIds(ids: Set<String>) {
        mutex.withLock {
            if (ids == pinnedIds && entries.values.all { it.pinned == (it.bookId in ids) }) return@withLock
            pinnedIds = ids
            entries = entries.mapValues { (id, e) -> if (e.pinned == (id in ids)) e else e.copy(pinned = id in ids) }
            persistLocked()
        }
        publishState()
    }

    // --- Arranque ---

    /** Concilia cache.json con lo que hay de verdad en disco (tras un corte, un kill o un cache.json perdido). */
    private suspend fun reconcile() = mutex.withLock {
        withContext(io) {
            booksDir.mkdirs()
            pagesRoot.mkdirs()
            val files = booksDir.listFiles().orEmpty().filter { it.isFile }
            val names = files.map { it.name }.toSet()
            var changed = false
            for (f in files) {
                val name = f.name
                val orphan = when {
                    name.endsWith(PART_SUFFIX + META_SUFFIX) -> name.removeSuffix(META_SUFFIX) !in names
                    name.endsWith(PART_SUFFIX) -> name + META_SUFFIX !in names
                    name.endsWith(TMP_SUFFIX) -> true
                    else -> false
                }
                if (orphan) {
                    log("cache: borro el huérfano $name")
                    f.delete()
                }
            }
            val kept = entries.filterValues { File(it.file).isFile }.toMutableMap()
            if (kept.size != entries.size) changed = true
            for (f in files) {
                if (!f.isFile || !isCompleteName(f.name)) continue
                val id = idOf(f.name)
                val entry = kept[id]
                if (entry != null) {
                    if (File(entry.file).absolutePath == f.absolutePath) {
                        if (entry.bytes != f.length()) {
                            log("cache: ${f.name} no mide lo apuntado (${f.length()} ≠ ${entry.bytes}); lo borro")
                            f.delete()
                            kept.remove(id)
                            changed = true
                        }
                    } else {
                        f.delete() // otra copia del mismo libro (p. ej. de la versión anterior con otra extensión)
                    }
                    continue
                }
                changed = true
                if (!adoptOrphans) {
                    f.delete()
                    continue
                }
                val book = try {
                    library?.book(id)
                } catch (_: Throwable) {
                    null
                }
                when {
                    book == null -> kept[id] = CachedBook(id, unknownKey(f.length()), f.absolutePath, f.length(), f.lastModified(), id in pinnedIds)
                    book.size == f.length() -> kept[id] = CachedBook(id, book.contentKey, f.absolutePath, f.length(), f.lastModified(), id in pinnedIds)
                    else -> f.delete() // de otra versión: no sirve
                }
            }
            val pinFlagsFixed = kept.mapValues { (id, e) -> if (e.pinned == (id in pinnedIds)) e else e.copy(pinned = id in pinnedIds) }
            if (pinFlagsFixed != kept) changed = true
            entries = pinFlagsFixed
            val withPages = entries.keys.map(::safeName).toSet()
            pagesRoot.listFiles().orEmpty().filter { it.name !in withPages }.forEach { it.deleteRecursively() }
            if (changed) persistLocked()
        }
        publishState()
    }

    // --- Persistencia y estado ---

    /** Bajo [mutex]. */
    private suspend fun persistLocked() {
        val snapshot = CacheSnapshot(entries = entries.values.sortedBy { it.bookId }, pinned = pinnedIds)
        withContext(io) { store.save(snapshot) }
    }

    private fun buildState(): CacheState {
        val app = settings.app.value
        val list = entries.values.sortedByDescending { it.lastOpened }
        return CacheState(
            usedBytes = list.sumOf { it.bytes } + partialBytesOnDisk(),
            limitBytes = app.cacheLimitGb.toLong() * bytesPerGb,
            deviceFreeBytes = storage.freeBytes(booksDir),
            minFreeBytes = app.minFreeGb.toLong() * bytesPerGb,
            entries = list,
        )
    }

    private fun publishState() {
        _state.value = buildState()
    }

    private fun partialBytesOnDisk(): Long =
        booksDir.listFiles().orEmpty().filter { it.name.endsWith(PART_SUFFIX) }.sumOf { it.length() }

    private fun partFile(bookId: String) = File(booksDir, safeName(bookId) + PART_SUFFIX)

    private fun deletePartial(bookId: String) {
        val part = partFile(bookId)
        part.delete()
        DriveRestApi.partialVersionFile(part).delete()
    }

    companion object {
        /** Gigabyte decimal, como lo cuenta Android en Ajustes > Almacenamiento. */
        const val GB = 1_000_000_000L

        /** Para abrir un libro se acepta pasar del tope, pero nunca dejar la tablet con menos de esto libre. */
        const val READ_FLOOR_BYTES = 512L * 1_000_000L
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_VERIFY_ATTEMPTS = 2

        internal const val PART_SUFFIX = ".part"
        internal const val META_SUFFIX = ".meta"
        private const val UNKNOWN_KEY_PREFIX = "?:"

        fun contentKeyOf(md5: String?, id: String, size: Long) = "${md5 ?: id}:$size"
        internal fun unknownKey(size: Long) = "$UNKNOWN_KEY_PREFIX$size"

        /** ¿La copia apuntada es la de este libro? Una adoptada sin índice vale si mide lo mismo. */
        internal fun matches(entry: CachedBook, book: Book): Boolean =
            entry.contentKey == book.contentKey ||
                (entry.contentKey.startsWith(UNKNOWN_KEY_PREFIX) && entry.bytes == book.size)

        /** Los id de Drive ya son seguros ([A-Za-z0-9_-]); por si acaso, nada que pueda salirse de la carpeta. */
        internal fun safeName(bookId: String): String = bookId.replace(Regex("[^A-Za-z0-9_-]"), "_")

        internal fun idOf(fileName: String): String = fileName.substringBefore('.')

        internal fun isPartialName(name: String) =
            name.endsWith(PART_SUFFIX) || name.endsWith(PART_SUFFIX + META_SUFFIX)

        internal fun isCompleteName(name: String) =
            '.' in name && !isPartialName(name) && !name.endsWith(TMP_SUFFIX) && !name.endsWith(META_SUFFIX)

        /** Extensión real (por los bytes); si no se reconoce, la del nombre original; si tampoco, «bin». */
        internal fun extensionFor(kind: ArchiveKind, originalName: String): String = when (kind) {
            ArchiveKind.ZIP -> "cbz"
            ArchiveKind.RAR4, ArchiveKind.RAR4_SOLID, ArchiveKind.RAR5 -> "cbr"
            ArchiveKind.SEVEN_ZIP -> "cb7"
            ArchiveKind.PDF -> "pdf"
            ArchiveKind.UNKNOWN -> originalName.substringAfterLast('.', "")
                .lowercase()
                .filter { it in 'a'..'z' || it in '0'..'9' }
                .take(8)
                .takeIf { it.isNotEmpty() && it !in RESERVED_EXTENSIONS }
                ?: "bin"
        }

        private val RESERVED_EXTENSIONS = setOf("part", "meta", "tmp")

        internal suspend fun md5Hex(file: File): String = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("MD5")
            val buffer = ByteArray(256 * 1024)
            file.inputStream().use { input ->
                var sinceCheck = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    sinceCheck += n
                    if (sinceCheck >= 16L * 1024 * 1024) {
                        sinceCheck = 0
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
