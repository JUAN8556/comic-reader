package mx.jano.comics.reader

import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mx.jano.comics.archive.ComicArchive
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * Extrae páginas del archivo a [dir] con UN solo trabajador en IO (pool acotado: el archivo se lee de uno en uno,
 * `synchronized(archive)`; el contrato no promete que ComicArchive admita hilos simultáneos) y una cola con
 * prioridad ([ExtractionPlanner]): lo que hay en pantalla primero, luego la ventana de pliegos de alrededor
 * (delante antes que detrás) y, si [sweep], el resto del libro para conocer el tamaño de cada página.
 *
 * - Una sola extracción por página: lo ya extraído (de esta sesión o de otra: la carpeta sobrevive) no se repite.
 *   Nunca se relee el archivo entero por página: cada página es una llamada a [ComicArchive.extractPage].
 * - Escritura atómica: se extrae a `<n>.<ext>.part` y se renombra; nunca queda una página a medias con el nombre bueno.
 * - Tras extraer, se lee el tamaño de la imagen con `inJustDecodeBounds` ([readBounds]) y se publica en [sizes]:
 *   de ahí sale el emparejamiento de la doble página.
 * - Si falla, la página queda marcada y no se reintenta sola; [forget] la borra para que «Reintentar» vuelva a extraer.
 */
internal class PageLoader(
    private val archive: ComicArchive,
    private val dir: File,
    private val readBounds: (File) -> PageSize? = ::decodeBounds,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    val pageCount: Int = runCatching { archive.pageCount }.getOrDefault(0)

    // Todo bajo [lock].
    private val done = HashMap<Int, File>()
    private val failed = HashMap<Int, Throwable>()
    private val waiters = HashMap<Int, MutableList<CompletableDeferred<File>>>()
    /** Páginas que hay que borrar de disco antes de volver a extraerlas («Reintentar» tras un fallo al decodificar). */
    private val forgotten = HashSet<Int>()
    private var window: List<Int> = emptyList()
    private var focus: Int = 0

    /** Barrer el libro entero en los ratos libres (solo hace falta en doble página). */
    @Volatile var sweep: Boolean = false
        set(value) {
            field = value
            if (value) wake.trySend(Unit)
        }

    private val _sizes = MutableStateFlow<Map<Int, PageSize>>(emptyMap())
    /** Tamaños conocidos (índice → píxeles). */
    val sizes: StateFlow<Map<Int, PageSize>> = _sizes.asStateFlow()

    // Repaso de lo que ya está en disco de otra sesión: solo del trabajador, por tandas y cuando no hay nada urgente.
    private var scanCursor = 0
    @Volatile private var scanned = false

    init {
        scope.launch { workLoop() }
    }

    /** La página [index] en disco; si no está, se extrae con prioridad máxima. Lanza si la extracción falla. */
    suspend fun page(index: Int): File {
        require(index in 0 until pageCount) { "página fuera de rango: $index de $pageCount" }
        val deferred = synchronized(lock) {
            done[index]?.let { if (it.isFile) return it else done.remove(index) }
            failed.remove(index)
            CompletableDeferred<File>().also { waiters.getOrPut(index) { mutableListOf() }.add(it) }
        }
        wake.trySend(Unit)
        try {
            return deferred.await()
        } finally {
            synchronized(lock) {
                val list = waiters[index]
                if (list != null) {
                    list.remove(deferred)
                    if (list.isEmpty()) waiters.remove(index)
                }
            }
        }
    }

    /** La página ya extraída, sin esperar ni pedirla (sin tocar el disco: se llama desde la composición). */
    fun pageIfReady(index: Int): File? = synchronized(lock) { done[index] }

    /** Ventana de páginas que conviene tener extraídas alrededor de [focusPage] (sustituye a la anterior). */
    fun setWindow(focusPage: Int, pages: List<Int>) {
        synchronized(lock) {
            focus = focusPage
            window = pages
        }
        wake.trySend(Unit)
    }

    /**
     * Olvida la página (p. ej. si Coil no pudo decodificarla) para que la siguiente petición la reextraiga. No toca el
     * disco ni el archivo aquí (se llama desde la interfaz y el archivo puede estar ocupado segundos con un RAR sólido):
     * el trabajador borra el fichero viejo justo antes de extraerla de nuevo.
     */
    fun forget(index: Int) {
        if (index !in 0 until pageCount) return
        synchronized(lock) {
            done.remove(index)
            failed.remove(index)
            forgotten.add(index)
        }
    }

    override fun close() {
        scope.cancel()
        wake.close()
        val pendingWaiters = synchronized(lock) { waiters.values.flatten().also { waiters.clear() } }
        pendingWaiters.forEach { it.cancel(CancellationException("lector cerrado")) }
    }

    /** Nombre de la página dentro del archivo (una consulta a la lista de entradas, sin E/S). */
    private fun pageName(index: Int): String = archive.pageName(index)

    /**
     * Un paso del repaso de lo que ya está en disco (de otra sesión): se da por extraído y se lee su tamaño
     * (cabecera, milisegundos). Va por tandas para no retrasar la primera página de un libro grande, y publica los
     * tamaños por tandas para no rehacer el emparejamiento a cada página.
     */
    private fun scanStep(batch: Int = SCAN_BATCH) {
        val found = HashMap<Int, File>()
        val sizes = HashMap<Int, PageSize>()
        var n = 0
        while (scanCursor < pageCount && n < batch) {
            val i = scanCursor++
            n++
            if (synchronized(lock) { done.containsKey(i) || i in forgotten }) continue
            if (!dir.isDirectory) {
                scanCursor = pageCount
                break
            }
            val f = runCatching { PageFiles.fileFor(dir, i, pageName(i)) }.getOrNull() ?: continue
            if (f.isFile && f.length() > 0L) {
                found[i] = f
                runCatching { readBounds(f) }.getOrNull()?.let { sizes[i] = it }
            }
        }
        if (found.isNotEmpty()) synchronized(lock) { found.forEach { (i, f) -> done.putIfAbsent(i, f) } }
        if (sizes.isNotEmpty()) _sizes.update { it + sizes }
        completeWaitersOfDonePages()
        if (scanCursor >= pageCount) {
            scanned = true
            ReaderTrace.log("repaso de la carpeta de páginas hecho")
        }
    }

    private suspend fun workLoop() {
        while (scope.isActive) {
            completeWaitersOfDonePages()
            val next = synchronized(lock) {
                ExtractionPlanner.pick(
                    pageCount = pageCount,
                    focus = focus,
                    urgent = waiters.keys,
                    window = window,
                    isDone = { done.containsKey(it) },
                    isFailed = { failed.containsKey(it) },
                    sweep = sweep && scanned,
                )
            }
            if (next == null) {
                if (!scanned) {
                    scanStep()
                    continue
                }
                if (wake.receiveCatching().isClosed) return
                continue
            }
            val result = try {
                Result.success(extract(next))
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                ReaderTrace.log("pág. ${next + 1}: ERROR al extraer: ${t::class.java.simpleName}: ${t.message}")
                Result.failure(t)
            }
            val file = result.getOrNull()
            val size = file?.let { runCatching { readBounds(it) }.getOrNull() }
            val toComplete = synchronized(lock) {
                if (file != null) done[next] = file else failed[next] = result.exceptionOrNull()!!
                waiters.remove(next).orEmpty()
            }
            if (size != null) _sizes.update { it + (next to size) }
            toComplete.forEach { d ->
                if (file != null) d.complete(file) else d.completeExceptionally(result.exceptionOrNull()!!)
            }
        }
    }

    /** Quien espera una página que ya está hecha (la encontró el repaso mientras llegaba la petición), la recibe. */
    private fun completeWaitersOfDonePages() {
        val ready = synchronized(lock) {
            if (waiters.isEmpty()) return
            waiters.keys.filter { done.containsKey(it) }.map { i -> done.getValue(i) to waiters.remove(i).orEmpty() }
        }
        ready.forEach { (file, list) -> list.forEach { it.complete(file) } }
    }

    private fun extract(index: Int): File {
        val dest = PageFiles.fileFor(dir, index, pageName(index))
        if (synchronized(lock) { forgotten.remove(index) }) dest.delete()
        if (dest.isFile && dest.length() > 0L) {
            ReaderTrace.log("pág. ${index + 1}: ya estaba en disco (${dest.length() / 1024} KB)")
            return dest
        }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
            throw IOException("no se pudo crear la carpeta de páginas ${dir.absolutePath}")
        }
        val tmp = File(dir, dest.name + ".part")
        tmp.delete()
        val queuedAt = System.nanoTime()
        val extractMs = synchronized(archive) {
            val t0 = System.nanoTime()
            archive.extractPage(index, tmp)
            (System.nanoTime() - t0) / 1_000_000
        }
        val waitedMs = (System.nanoTime() - queuedAt) / 1_000_000 - extractMs
        ReaderTrace.log("pág. ${index + 1}: extraída en $extractMs ms (${tmp.length() / 1024} KB), $waitedMs ms esperando el archivo")
        if (!tmp.isFile || tmp.length() == 0L) {
            tmp.delete()
            throw IOException("la extracción de la página ${index + 1} dejó un fichero vacío")
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) throw IOException("no se pudo renombrar ${tmp.name} a ${dest.name}")
        }
        return dest
    }

    companion object {
        private const val SCAN_BATCH = 16

        /** Ancho y alto de la imagen sin decodificarla (solo la cabecera). Null si no es una imagen legible. */
        fun decodeBounds(file: File): PageSize? {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            return if (opts.outWidth > 0 && opts.outHeight > 0) PageSize(opts.outWidth, opts.outHeight) else null
        }
    }
}
