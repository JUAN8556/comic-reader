package mx.jano.comics.reader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import mx.jano.comics.archive.ComicArchive
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Extrae páginas del archivo a [dir] bajo demanda, en Dispatchers.IO.
 *
 * - Una sola extracción por página: el mapa de Deferred hace que dos peticiones simultáneas de la misma página
 *   (la visible y la precargada por el pager) esperen al mismo trabajo.
 * - La extracción sigue aunque la página salga de pantalla: cuando el lector vuelva, ya estará en disco.
 * - Escritura atómica: se extrae a `<n>.<ext>.part` y se renombra; nunca queda una página a medias con el nombre bueno.
 * - El archivo se usa con `synchronized(archive)`: el contrato no promete que ComicArchive admita hilos simultáneos.
 * - Si falla, se olvida el Deferred para que «Reintentar» vuelva a extraer.
 */
internal class PageLoader(
    private val archive: ComicArchive,
    private val dir: File,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<Int, Deferred<File>>()

    suspend fun page(index: Int): File {
        val job = jobs.computeIfAbsent(index) { scope.async { extract(index) } }
        try {
            return job.await()
        } catch (e: CancellationException) {
            // Cancelaron a quien esperaba (la página salió de la composición): el trabajo sigue en su sitio.
            throw e
        } catch (t: Throwable) {
            jobs.remove(index, job)
            ReaderTrace.log("pág. ${index + 1}: ERROR al extraer: ${t::class.java.simpleName}: ${t.message}")
            throw t
        }
    }

    /** Borra la página de la caché (p. ej. si Coil no pudo decodificarla) para que la siguiente petición la reextraiga. */
    fun forget(index: Int) {
        jobs.remove(index)
        runCatching { PageFiles.fileFor(dir, index, archive.pageName(index)).delete() }
    }

    private fun extract(index: Int): File {
        val dest = PageFiles.fileFor(dir, index, archive.pageName(index))
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

    override fun close() {
        scope.cancel()
    }
}
