package mx.jano.comics.library

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mx.jano.comics.archive.ArchiveKind
import mx.jano.comics.archive.ComicArchives
import mx.jano.comics.archive.OpenOptions
import mx.jano.comics.archive.open
import mx.jano.comics.archive.sniffHeader
import mx.jano.comics.metadata.IndexEntry
import mx.jano.comics.metadata.IndiceSync
import mx.jano.comics.metadata.TolerantJson
import mx.jano.comics.settings.AtomicFiles
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/** Convierte la imagen de una portada en el fichero que se guarda (en la app, WebP de ~300 px con Bitmap). */
internal fun interface CoverEncoder {
    /** Escribe [dest] a partir de los bytes de una imagen; false si no es una imagen que se pueda decodificar. */
    fun encode(image: ByteArray, dest: File): Boolean
}

/**
 * [CoverStore]: portadas en files/covers/<md5>.webp (o `id_<id>.webp` si Drive no da md5).
 *
 * Orden para conseguir una ([ensureCover]):
 * 1. Ya en disco (importada de `.indice/portadas.zip` con [importIndexCovers], o generada antes).
 * 2. Por prefijo del archivo en Drive, sin bajarlo entero (se miran los primeros bytes para saber el formato real):
 *    - ZIP/CBZ: últimos 64 KB → directorio central → primera imagen en orden natural ([ZipRemote]);
 *    - RAR4, RAR4 sólido, RAR5: un prefijo creciente de [RAR_PREFIX_STEPS] (1, 2, 4 y 8 MB, pidiendo solo lo que
 *      falta) que se abre con [ComicArchives] hasta que la primera página sale entera;
 *    - 7z y PDF: no se puede por prefijo → null (la del PDF se genera al abrirlo: [coverFromLocalFile]).
 * 3. La imagen se reduce con [encoder] (WebP ~300 px; si ya es pequeña se guarda tal cual).
 *
 * Nunca más de [MAX_PARALLEL] portadas remotas a la vez; dos peticiones de la misma portada esperan al mismo
 * trabajo; un fallo se recuerda para no repetirlo en cada desplazamiento de la rejilla: [RETRY_AFTER_MS] si el
 * archivo no da portada por prefijo, [RETRY_AFTER_ERROR_MS] si fue la red o Drive.
 */
class DriveCoverStore internal constructor(
    private val coversDir: File,
    private val workDir: File,
    private val ranges: RangeReader?,
    private val indice: IndiceSync?,
    private val encoder: CoverEncoder,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) : CoverStore {

    private val gate = Semaphore(MAX_PARALLEL)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<File?>>()
    /** Clave → cuándo se puede volver a intentar (tras un fallo). */
    private val retryAt = ConcurrentHashMap<String, Long>()
    private val importMutex = Mutex()

    override fun coverFile(book: Book): File? = fileFor(book).takeIf { it.isFile && it.length() > 0 }

    override suspend fun ensureCover(book: Book): File? {
        coverFile(book)?.let { return it }
        val key = keyOf(book)
        retryAt[key]?.let { if (clock() < it) return null }
        val mine = CompletableDeferred<File?>()
        val existing = inFlight.putIfAbsent(key, mine)
        if (existing != null) return existing.await()
        try {
            var retryIn = RETRY_AFTER_MS
            val result = try {
                gate.withPermit { withContext(Dispatchers.IO) { coverFile(book) ?: fetchRemote(book) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Red, cuota, permiso: se reintenta pronto. «No se puede por prefijo» (null sin error): mucho después.
                log("portada «${book.name}»: ${e::class.java.simpleName}: ${e.message}")
                retryIn = RETRY_AFTER_ERROR_MS
                null
            }
            if (result == null) retryAt[key] = clock() + retryIn else retryAt.remove(key)
            mine.complete(result)
            return result
        } catch (e: CancellationException) {
            // Quien esperaba a este trabajo recibe null (sin apuntarlo como fallo) y lo volverá a pedir.
            mine.complete(null)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    /**
     * Genera la portada a partir del archivo ya bajado entero (sirve para todo, PDF y 7z incluidos cuando el motor
     * lo lea). Lo llama quien abre el libro (lector o caché) si [coverFile] aún es null.
     */
    suspend fun coverFromLocalFile(book: Book, file: File): File? = withContext(Dispatchers.IO) {
        coverFile(book)?.let { return@withContext it }
        try {
            val image = firstPageOf(file) ?: return@withContext null
            encodeTo(book, image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("portada local «${book.name}»: ${e::class.java.simpleName}: ${e.message}")
            null
        }
    }

    private suspend fun fetchRemote(book: Book): File? {
        val reader = ranges ?: return null
        if (book.size <= 0) return null
        val head = reader.read(book.id, 0, minOf(HEAD_BYTES.toLong(), book.size).toInt())
        val image = when (sniffHeader(head, head.size)) {
            ArchiveKind.ZIP -> ZipRemote.firstImage(reader, book.id, book.size)
            ArchiveKind.RAR4, ArchiveKind.RAR4_SOLID, ArchiveKind.RAR5 -> rarByPrefix(reader, book)
            ArchiveKind.SEVEN_ZIP, ArchiveKind.PDF, ArchiveKind.UNKNOWN -> null
        } ?: return null
        return encodeTo(book, image)
    }

    private fun encodeTo(book: Book, image: ByteArray): File? {
        val dest = fileFor(book)
        coversDir.mkdirs()
        return if (encoder.encode(image, dest) && dest.isFile && dest.length() > 0) dest else null
    }

    /** Prefijo creciente: se baja solo lo que falta en cada paso y se intenta sacar la primera página. */
    private suspend fun rarByPrefix(reader: RangeReader, book: Book): ByteArray? {
        val dir = File(workDir, "rar-" + keyOf(book)).apply { mkdirs() }
        val prefix = File(dir, "prefijo.rar")
        try {
            prefix.delete()
            var have = 0L
            for (step in RAR_PREFIX_STEPS) {
                val want = minOf(step.toLong(), book.size)
                if (want > have) {
                    val more = reader.read(book.id, have, (want - have).toInt())
                    if (more.isEmpty()) break
                    prefix.appendBytes(more)
                    have += more.size
                }
                firstPageOf(prefix, dir)?.let { return it }
                if (have >= book.size) break
            }
            return null
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Primera página de un archivo local (completo o un prefijo), o null si no sale entera. */
    private fun firstPageOf(file: File, scratch: File = File(workDir, "local").apply { mkdirs() }): ByteArray? {
        val out = File.createTempFile("pagina-", ".img", scratch)
        return try {
            ComicArchives.open(file, OpenOptions(pdfTargetWidthPx = PDF_COVER_WIDTH, solidStagingParent = scratch)).use { archive ->
                if (archive.pageCount == 0) return null
                archive.extractPage(0, out)
                out.readBytes().takeIf { it.isNotEmpty() }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        } finally {
            out.delete()
        }
    }

    // ------------------------------------------------------------------ importación de .indice/portadas.zip

    @Serializable
    internal data class ImportState(val md5: String? = null, val size: Long? = null, val count: Int = 0)

    override suspend fun importIndexCovers(): Int = importMutex.withLock {
        val sync = indice ?: return@withLock 0
        val remote = sync.listRemote()?.get(IndiceSync.PORTADAS) ?: return@withLock 0
        val stateFile = File(coversDir, IMPORT_STATE)
        val previous = AtomicFiles.readWithFallback(stateFile) { AtomicFiles.json.decodeFromString(ImportState.serializer(), it) }
        if (previous != null && remote.md5Checksum != null && previous.md5 == remote.md5Checksum && previous.size == remote.size) {
            return@withLock 0
        }
        // Para las entradas que no se llamen <md5>.webp hace falta indice.jsonl (portada → md5).
        sync.sync(setOf(IndiceSync.INDICE))
        val byCoverName = withContext(Dispatchers.IO) { coverNamesFromIndex(sync.local(IndiceSync.INDICE)) }
        val zip = File(workDir, IndiceSync.PORTADAS)
        workDir.mkdirs()
        try {
            sync.download(remote, zip, resumable = true)
            val count = withContext(Dispatchers.IO) { extractCovers(zip, byCoverName) }
            AtomicFiles.writeText(
                stateFile,
                AtomicFiles.json.encodeToString(ImportState.serializer(), ImportState(remote.md5Checksum, remote.size, count)),
            )
            log("portadas: importadas $count de portadas.zip")
            count
        } finally {
            zip.delete()
        }
    }

    /** nombre dentro de portadas.zip (sin carpetas) → md5, de indice.jsonl. */
    private fun coverNamesFromIndex(file: File?): Map<String, String> {
        if (file == null) return emptyMap()
        val text = try { file.readText(Charsets.UTF_8) } catch (_: IOException) { return emptyMap() }
        return TolerantJson.parseLines(text, IndexEntry.serializer()).items.asSequence()
            .filter { !it.portada.isNullOrBlank() && !it.md5.isNullOrBlank() }
            .associate { it.portada!!.substringAfterLast('/').lowercase(Locale.ROOT) to it.md5!!.lowercase(Locale.ROOT) }
    }

    internal fun extractCovers(zip: File, byCoverName: Map<String, String>): Int {
        coversDir.mkdirs()
        var count = 0
        ZipFile(zip).use { z ->
            for (entry in z.entries()) {
                if (entry.isDirectory) continue
                val base = entry.name.replace('\\', '/').substringAfterLast('/')
                if (base.startsWith(".")) continue
                val stem = base.substringBeforeLast('.').lowercase(Locale.ROOT)
                val md5 = when {
                    MD5.matches(stem) -> stem
                    else -> byCoverName[base.lowercase(Locale.ROOT)]
                } ?: continue
                val dest = File(coversDir, "$md5.webp")
                if (dest.isFile && entry.size >= 0 && dest.length() == entry.size) {
                    count++
                    continue
                }
                val bytes = z.getInputStream(entry).use { it.readBytes() }
                if (bytes.isEmpty()) continue
                AtomicFiles.write(dest, bytes)
                count++
            }
        }
        return count
    }

    // ------------------------------------------------------------------ claves

    private fun fileFor(book: Book): File = File(coversDir, keyOf(book) + ".webp")

    internal companion object {
        const val MAX_PARALLEL = 3
        const val HEAD_BYTES = 1024
        const val RETRY_AFTER_MS = 30L * 60 * 1000
        const val RETRY_AFTER_ERROR_MS = 60L * 1000
        const val IMPORT_STATE = ".importado.json"
        const val PDF_COVER_WIDTH = 600
        val RAR_PREFIX_STEPS = listOf(1 shl 20, 2 shl 20, 4 shl 20, 8 shl 20)
        private val MD5 = Regex("^[0-9a-f]{32}$")
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")

        fun keyOf(book: Book): String =
            book.md5?.lowercase(Locale.ROOT)?.takeIf { MD5.matches(it) } ?: ("id_" + book.id.replace(UNSAFE, "_"))
    }
}
