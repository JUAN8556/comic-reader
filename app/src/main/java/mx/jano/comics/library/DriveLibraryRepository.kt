package mx.jano.comics.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import mx.jano.comics.auth.NeedsConsentException
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.settings.AtomicFiles
import mx.jano.comics.settings.PathKeys
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** La carpeta raíz: su id de Drive y su nombre. */
internal data class RootRef(val id: String, val name: String)

/**
 * [LibraryRepository]: espejo en memoria de la carpeta raíz de Drive, persistido en files/index.json.
 *
 * - Al construirse carga index.json (y su `.bak`) en segundo plano; [diskLoaded] dice cuándo terminó.
 * - [rescan] escanea por niveles: los ids de un nivel van en lotes de [DriveAccess.PARENTS_PER_QUERY] padres por
 *   consulta, [DriveAccess.PARALLEL] consultas a la vez; [scanState] cuenta carpetas y libros en vivo. Dos
 *   [rescan] a la vez comparten el mismo escaneo, que corre en el ámbito del repositorio (no se corta si la
 *   pantalla que lo pidió desaparece).
 * - Se ignoran carpetas y ficheros que empiezan por «.» (`.indice`, `._x.cbr` de la Mac) y los accesos directos.
 *   Un libro es un fichero con extensión de cómic ([BOOK_EXTENSIONS]); el formato real lo decide el sniffer al abrirlo.
 * - Si el escaneo falla, el índice anterior se queda y [scanState] pasa a [ScanState.Error].
 * - Las consultas ([children], [nextAfter]…) leen una instantánea inmutable con mapas precalculados: O(1) o
 *   O(hijos), seguras desde cualquier hilo.
 */
class DriveLibraryRepository internal constructor(
    private val indexFile: File,
    private val lister: ChildLister,
    private val resolveRoot: suspend () -> RootRef,
    /** Id de la raíz configurada ahora mismo (para descartar un index.json de otra raíz al cargar). */
    private val configuredRootId: () -> String?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : LibraryRepository {

    private val indexFlow = MutableStateFlow<LibraryIndex?>(null)
    private val stateFlow = MutableStateFlow<ScanState>(ScanState.Idle)
    private val diskLoadedFlow = MutableStateFlow(false)

    override val index: StateFlow<LibraryIndex?> = indexFlow.asStateFlow()
    override val scanState: StateFlow<ScanState> = stateFlow.asStateFlow()

    /** true cuando ya se intentó cargar index.json (haya o no). Hasta entonces, `index == null` no significa «vacía». */
    val diskLoaded: StateFlow<Boolean> = diskLoadedFlow.asStateFlow()

    @Volatile private var snapshot: Snapshot? = null
    private val loadJob: Job = scope.launch(Dispatchers.IO) { loadFromDisk() }
    private val inFlightLock = Any()
    private var inFlight: Deferred<LibraryIndex>? = null

    // ------------------------------------------------------------------ consultas

    override fun folder(id: String): Folder? = snapshot?.folders?.get(id)

    override fun book(id: String): Book? = snapshot?.books?.get(id)

    /** Libro por su ruta relativa (normalizada como las del índice), o null. */
    fun bookByPath(path: String): Book? = snapshot?.booksByPath?.get(PathKeys.normalize(path))

    /** Carpeta por su ruta relativa («» = la raíz), o null. */
    fun folderByPath(path: String): Folder? = snapshot?.foldersByPath?.get(PathKeys.normalize(path))

    override fun children(folderId: String): Pair<List<Folder>, List<Book>> {
        val s = snapshot ?: return emptyList<Folder>() to emptyList()
        return (s.childFolders[folderId] ?: emptyList()) to (s.childBooks[folderId] ?: emptyList())
    }

    override fun booksUnder(folderId: String): List<Book> {
        val s = snapshot ?: return emptyList()
        if (folderId !in s.folders) return emptyList()
        val out = ArrayList<Book>()
        val stack = ArrayDeque<String>().apply { addLast(folderId) }
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            s.childBooks[id]?.let { out += it }
            s.childFolders[id]?.forEach { stack.addLast(it.id) }
        }
        out.sortWith(compareBy(PathOrder) { it.path })
        return out
    }

    override fun nextAfter(book: Book): Book? = neighbour(book, +1)

    override fun previousBefore(book: Book): Book? = neighbour(book, -1)

    private fun neighbour(book: Book, step: Int): Book? {
        val s = snapshot ?: return null
        val siblings = s.childBooks[book.folderId] ?: return null
        val pos = s.positionInFolder[book.id]
        if (pos != null && siblings.getOrNull(pos)?.id == book.id) return siblings.getOrNull(pos + step)
        // Libro que ya no está en el índice (renombrado, reescaneo): se busca su sitio por nombre.
        return if (step > 0) siblings.firstOrNull { Naming.natural.compare(it.name, book.name) > 0 }
        else siblings.lastOrNull { Naming.natural.compare(it.name, book.name) < 0 }
    }

    // ------------------------------------------------------------------ escaneo

    override suspend fun rescan(): LibraryIndex {
        val job = synchronized(inFlightLock) {
            inFlight?.takeIf { it.isActive } ?: scope.async { runScan() }.also { inFlight = it }
        }
        return job.await()
    }

    private suspend fun runScan(): LibraryIndex {
        loadJob.join()
        val t0 = clock()
        stateFlow.value = ScanState.Scanning(0, 0)
        try {
            val root = resolveRoot()
            val scanned = LevelScanner(lister, log).scan(root) { folders, books ->
                stateFlow.value = ScanState.Scanning(folders, books)
            }
            // Una raíz que de golpe sale vacía (carpeta movida a la papelera, permiso retirado, respuesta rara) no
            // borra una biblioteca que tenía libros: se conserva la anterior y se avisa.
            val previous = indexFlow.value
            if (scanned.books.isEmpty() && scanned.folders.size <= 1 && previous != null &&
                previous.rootId == root.id && previous.books.isNotEmpty()
            ) {
                throw EmptyRootException(LibraryStrings.ROOT_EMPTY)
            }
            val index = scanned.copy(scannedAt = clock())
            publish(index)
            withContext(Dispatchers.IO) {
                try {
                    AtomicFiles.writeText(indexFile, AtomicFiles.json.encodeToString(LibraryIndex.serializer(), index), keepBackup = true)
                } catch (e: IOException) {
                    log("biblioteca: no se pudo guardar index.json: ${e.message}")
                }
            }
            val ms = clock() - t0
            log("biblioteca: ${index.folders.size} carpetas, ${index.books.size} libros en $ms ms")
            stateFlow.value = ScanState.Done(index.folders.size, index.books.size, ms)
            return index
        } catch (e: CancellationException) {
            stateFlow.value = ScanState.Idle
            throw e
        } catch (e: Exception) {
            log("biblioteca: escaneo fallido: ${e::class.java.simpleName}: ${e.message}")
            stateFlow.value = ScanState.Error(describe(e))
            throw e
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is RootMissingException -> e.message ?: LibraryStrings.NO_ROOT
        is EmptyRootException -> e.message ?: LibraryStrings.ROOT_EMPTY
        is NeedsConsentException -> LibraryStrings.NEEDS_CONSENT
        is DriveHttpException -> LibraryStrings.driveError(e.message)
        is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> LibraryStrings.OFFLINE
        is IOException -> LibraryStrings.scanFailed(e.message)
        else -> LibraryStrings.scanFailed(e.message ?: e::class.java.simpleName)
    }

    private fun publish(index: LibraryIndex) {
        snapshot = Snapshot(index)
        indexFlow.value = index
    }

    private fun loadFromDisk() {
        try {
            AtomicFiles.cleanTemporaries(indexFile)
            val loaded = AtomicFiles.readWithFallback(indexFile, onProblem = log) { text ->
                AtomicFiles.json.decodeFromString(LibraryIndex.serializer(), text)
            }
            if (loaded != null) {
                val expected = configuredRootId()
                if (expected != null && expected != loaded.rootId) {
                    log("biblioteca: index.json es de otra raíz (${loaded.rootId}); se ignora")
                } else if (indexFlow.value == null) {
                    publish(loaded)
                }
            }
        } catch (e: Exception) {
            log("biblioteca: no se pudo cargar index.json: ${e.message}")
        } finally {
            diskLoadedFlow.value = true
        }
    }

    /** Estructuras derivadas de un índice, calculadas una vez. */
    private class Snapshot(index: LibraryIndex) {
        val folders: Map<String, Folder> = index.folders.associateBy { it.id }
        val books: Map<String, Book> = index.books.associateBy { it.id }
        val foldersByPath: Map<String, Folder> = index.folders.associateBy { it.path }
        val booksByPath: Map<String, Book> = index.books.associateBy { it.path }
        val childFolders: Map<String, List<Folder>> = index.folders.asSequence()
            .filter { it.parentId != null }
            .groupBy { it.parentId!! }
            .mapValues { (_, list) -> list.sortedWith(compareBy(Naming.natural) { it.name }) }
        val childBooks: Map<String, List<Book>> = index.books.groupBy { it.folderId }
            .mapValues { (_, list) -> list.sortedWith(compareBy(Naming.natural) { it.name }) }
        val positionInFolder: Map<String, Int> = HashMap<String, Int>().apply {
            childBooks.values.forEach { list -> list.forEachIndexed { i, b -> put(b.id, i) } }
        }
    }

    companion object {
        /** Extensiones que cuentan como libro (en minúsculas). */
        val BOOK_EXTENSIONS: Set<String> = setOf("cbz", "cbr", "cb7", "cbt", "pdf", "zip", "rar", "7z")

        fun isBookName(name: String): Boolean {
            if (name.startsWith(".")) return false
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return false
            return name.substring(dot + 1).lowercase(Locale.ROOT) in BOOK_EXTENSIONS
        }
    }
}

/** No hay carpeta raíz configurada ni se encontró «Cómics». */
internal class RootMissingException(message: String) : IllegalStateException(message)

/** La raíz salió vacía y antes tenía libros: no se sustituye el índice. */
internal class EmptyRootException(message: String) : IllegalStateException(message)

/** Orden natural de rutas segmento a segmento («Serie/01» antes que «Serie 2/01»). */
internal object PathOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val sa = a.split('/')
        val sb = b.split('/')
        for (i in 0 until minOf(sa.size, sb.size)) {
            val c = Naming.natural.compare(sa[i], sb[i])
            if (c != 0) return c
        }
        return sa.size - sb.size
    }
}

/**
 * El escaneo por niveles, aparte del repositorio para probarlo con un [ChildLister] falso.
 * [onProgress] recibe (carpetas, libros) vistos hasta ahora, desde varios hilos.
 */
internal class LevelScanner(
    private val lister: ChildLister,
    private val log: (String) -> Unit = {},
    private val parentsPerQuery: Int = DriveAccess.PARENTS_PER_QUERY,
    private val parallel: Int = DriveAccess.PARALLEL,
) {
    private class RawFolder(val id: String, val name: String, val parentId: String?, val path: String)

    suspend fun scan(root: RootRef, onProgress: (folders: Int, books: Int) -> Unit = { _, _ -> }): LibraryIndex {
        val folders = ArrayList<RawFolder>()
        val books = ArrayList<Book>()
        val rootFolder = RawFolder(root.id, root.name, null, "")
        folders += rootFolder
        val pathOf = HashMap<String, String>().apply { put(root.id, "") }
        val visited = HashSet<String>().apply { add(root.id) }
        val seenFolders = AtomicInteger(1)
        val seenBooks = AtomicInteger(0)
        var level = listOf(rootFolder)
        var depth = 0
        val gate = Semaphore(parallel)
        while (level.isNotEmpty()) {
            if (depth >= MAX_DEPTH) {
                log("escáner: ${LibraryStrings.TOO_DEEP}")
                break
            }
            val levelIds = level.mapTo(HashSet()) { it.id }
            val results: List<List<ScanItem>> = coroutineScope {
                level.map { it.id }.chunked(parentsPerQuery).map { batch ->
                    async {
                        gate.withPermit {
                            lister.list(batch).also { items ->
                                onProgress(
                                    seenFolders.addAndGet(items.count { it.isFolder && !it.name.startsWith(".") }),
                                    seenBooks.addAndGet(items.count { !it.isFolder && DriveLibraryRepository.isBookName(it.name) }),
                                )
                            }
                        }
                    }
                }.awaitAll()
            }
            val next = ArrayList<RawFolder>()
            for (item in results.asSequence().flatten()) {
                val parent = item.parents?.firstOrNull { it in levelIds } ?: continue
                val parentPath = pathOf[parent] ?: continue
                when {
                    item.name.startsWith(".") -> {}
                    item.isFolder -> {
                        if (!visited.add(item.id)) continue
                        val path = PathKeys.join(parentPath, item.name)
                        pathOf[item.id] = path
                        val f = RawFolder(item.id, item.name, parent, path)
                        folders += f
                        next += f
                    }
                    item.mimeType == ScanItem.SHORTCUT_MIME -> {}
                    DriveLibraryRepository.isBookName(item.name) -> books += Book(
                        id = item.id,
                        name = item.name,
                        folderId = parent,
                        path = PathKeys.join(parentPath, item.name),
                        size = item.size ?: 0L,
                        md5 = item.md5Checksum,
                        headRevisionId = item.headRevisionId,
                        modifiedTime = item.modifiedTime,
                        mimeType = item.mimeType,
                    )
                }
            }
            level = next
            depth++
        }
        return build(root, folders, books)
    }

    private fun build(root: RootRef, raw: List<RawFolder>, books: List<Book>): LibraryIndex {
        // Un libro que aparezca dos veces (varios padres en el mismo nivel) cuenta una.
        val uniqueBooks = books.distinctBy { it.id }
        val directBooks = uniqueBooks.groupingBy { it.folderId }.eachCount()
        val directFolders = raw.filter { it.parentId != null }.groupingBy { it.parentId!! }.eachCount()
        val total = HashMap<String, Int>()
        // Del nivel más hondo hacia arriba: los padres siempre van antes que sus hijos en [raw].
        for (f in raw.asReversed()) {
            val mine = (directBooks[f.id] ?: 0) + (total[f.id] ?: 0)
            total[f.id] = mine
            f.parentId?.let { total[it] = (total[it] ?: 0) + mine }
        }
        val folders = raw.map { f ->
            Folder(
                id = f.id,
                name = f.name,
                parentId = f.parentId,
                path = f.path,
                bookCount = directBooks[f.id] ?: 0,
                folderCount = directFolders[f.id] ?: 0,
                totalBooks = total[f.id] ?: 0,
            )
        }.sortedWith(compareBy(PathOrder) { it.path })
        return LibraryIndex(
            rootId = root.id,
            rootName = root.name,
            scannedAt = 0L,
            folders = folders,
            books = uniqueBooks.sortedWith(compareBy(PathOrder) { it.path }),
        )
    }

    companion object {
        const val MAX_DEPTH = 64
    }
}
