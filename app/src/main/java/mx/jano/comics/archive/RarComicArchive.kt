package mx.jano.comics.archive

import com.github.junrar.Archive
import com.github.junrar.ArchiveOptions
import com.github.junrar.RarFormat
import com.github.junrar.UnrarCallback
import com.github.junrar.exception.BadRarArchiveException
import com.github.junrar.exception.CorruptHeaderException
import com.github.junrar.exception.CrcErrorException
import com.github.junrar.exception.InitDeciphererFailedException
import com.github.junrar.exception.MainHeaderNullException
import com.github.junrar.exception.MissingNextVolumeException
import com.github.junrar.exception.MissingPreviousVolumeException
import com.github.junrar.exception.NotRarArchiveException
import com.github.junrar.exception.RarException
import com.github.junrar.exception.UnsupportedDictionarySizeException
import com.github.junrar.exception.UnsupportedRarEncryptedException
import com.github.junrar.exception.UnsupportedRarMethodException
import com.github.junrar.exception.UnsupportedRarVersionException
import com.github.junrar.exception.WrongPasswordException
import com.github.junrar.rarfile.FileHeader
import com.github.junrar.volume.Volume
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * CBR con junrar 8.1.2 (RAR 1.5-4.x, sólido o no, y RAR5).
 *
 * - No sólido: cada página se saca directamente (junrar salta a su cabecera y descomprime solo esa entrada).
 * - Sólido: ver [SolidStager]; las páginas pasan por una caché en disco para no descomprimir O(n²).
 * - junrar no es seguro entre hilos: todo acceso al [Archive] va con [lock].
 */
internal class RarComicArchive private constructor(
    private val source: File,
    private val archive: Archive,
    override val kind: ArchiveKind,
    /** Sólido según junrar (bandera MHD_SOLID de RAR4 o la bandera de sólido de alguna entrada, que es lo que mira `extractFile`). */
    internal val isSolid: Boolean,
    /** Bandera MHD_SOLID del MAIN_HEAD según junrar (`getMainHeader().isSolid()`); `null` en RAR5, que no tiene ese bloque. */
    internal val mainHeaderSolid: Boolean?,
    private val pages: List<PageRef<FileHeader>>,
    private val entryIndexOfPage: IntArray,
    private val allHeaders: List<FileHeader>,
    private val progress: PackedProgress,
    private val options: OpenOptions,
) : ComicArchive {

    private val lock = Any()
    private val stager: SolidStager? = if (isSolid) SolidStager(entryIndexOfPage, ::decompressEntry) else null
    private var stagingDir: File? = null
    @Volatile private var closed = false

    override val pageCount: Int get() = pages.size
    override fun pageName(index: Int): String = pages[index].name

    /** Formato según junrar (RAR15 = 1.5-4.x, RAR50 = RAR5). */
    internal val format: RarFormat? get() = archive.format

    /** Bytes comprimidos que junrar ha leído desde que se abrió (para medir cuánto relee cada página). */
    internal val packedBytesRead: Long get() = progress.packedRead

    /** Método de la primera página: RAR4 0x30 = guardado … 0x35 = máxima; RAR5 0 = guardado … 5. */
    internal val firstPageMethod: Int? get() = pages.firstOrNull()?.handle?.unpMethod?.toInt()

    /** Ventana (diccionario) mayor entre las páginas, en bytes. */
    internal val maxWindowBytes: Long
        get() = pages.maxOfOrNull { windowBytes(it.handle) } ?: 0L

    /** Cabeceras que junrar no pudo leer (las tolera y sigue). */
    internal val brokenHeaders: Int get() = archive.headerFailures.size

    /** Veces que se pidió una entrada al motor por la caché de sólidos. */
    internal val solidDecompressCalls: Int get() = stager?.decompressCalls ?: 0

    override fun extractPage(index: Int, dest: File) {
        val page = pages[index]
        synchronized(lock) {
            if (closed) throw IOException(ArchiveTexts.closed(source))
            try {
                if (stager == null) {
                    writeAtomically(dest) { out -> archive.extractFile(page.handle, out) }
                } else {
                    val staged = stager.stage(stagingDirFor(dest), index)
                    writeAtomically(dest) { out -> staged.inputStream().use { it.copyToStream(out) } }
                }
            } catch (e: RarException) {
                throw mapRarException(e, kind, source)
            } catch (e: ComicArchiveException) {
                throw e
            } catch (e: IOException) {
                if (closed) throw IOException(ArchiveTexts.closed(source), e)
                throw e
            } catch (e: RuntimeException) {
                // junrar puede reventar con índices fuera de rango en datos dañados.
                throw CorruptArchiveException(kind, ArchiveTexts.corrupt(source, e.toString()), e)
            }
        }
    }

    private fun decompressEntry(entryIndex: Int, out: java.io.OutputStream) {
        archive.extractFile(allHeaders[entryIndex], out)
    }

    private fun stagingDirFor(dest: File): File {
        stagingDir?.let { return it }
        val parent = options.solidStagingParent ?: dest.absoluteFile.parentFile
            ?: throw IOException("Sin carpeta para $dest")
        val fingerprint = "${source.absolutePath}|${source.length()}|${source.lastModified()}".hashCode()
        val dir = File(parent, String.format(Locale.ROOT, ".solid-%08x", fingerprint))
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("No se pudo crear $dir")
        stagingDir = dir
        return dir
    }

    override fun close() {
        if (closed) return
        closed = true
        // Primero el canal (aborta una extracción en curso en otro hilo) y luego, con el lock, la caché.
        runCatching { archive.close() }
        synchronized(lock) {
            stagingDir?.deleteRecursively()
            stagingDir = null
        }
    }

    /** Cuenta los bytes comprimidos leídos; no hay más volúmenes que buscar (Drive da un fichero suelto). */
    internal class PackedProgress : UnrarCallback {
        @Volatile var packedRead = 0L
        override fun isNextVolumeReady(nextVolume: Volume?): Boolean = false
        override fun volumeProgressChanged(current: Long, total: Long) {
            packedRead = current
        }
    }

    companion object {
        fun open(file: File, sniffed: ArchiveKind, options: OpenOptions): RarComicArchive {
            val progress = PackedProgress()
            val maxDictionary = options.effectiveMaxDictionary()
            val archiveOptions = ArchiveOptions.builder()
                .maxDictionarySize(maxDictionary)
                .unrarCallback(progress)
                .build()
            val archive = try {
                Archive(file, archiveOptions)
            } catch (e: RarException) {
                throw mapRarException(e, sniffed, file, maxDictionary)
            } catch (e: RuntimeException) {
                throw CorruptArchiveException(sniffed, ArchiveTexts.corrupt(file, e.toString()), e)
            }
            try {
                return build(file, sniffed, archive, progress, options)
            } catch (t: Throwable) {
                runCatching { archive.close() }
                throw t
            }
        }

        private fun build(
            file: File,
            sniffed: ArchiveKind,
            archive: Archive,
            progress: PackedProgress,
            options: OpenOptions,
        ): RarComicArchive {
            val headersEncrypted = try {
                archive.isEncrypted
            } catch (e: MainHeaderNullException) {
                throw CorruptArchiveException(sniffed, ArchiveTexts.corrupt(file, "sin cabecera principal"), e)
            }
            if (headersEncrypted) throw EncryptedArchiveException(sniffed, ArchiveTexts.encrypted(file))

            val all: List<FileHeader> = archive.fileHeaders
            val indexed = all.withIndex().toList()
            val pages = pagesOf(indexed, { it.value.fileName ?: "" }, { it.value.isDirectory })
            if (pages.any { it.handle.value.isEncrypted }) throw EncryptedArchiveException(sniffed, ArchiveTexts.encrypted(file))

            val mainSolid = archive.mainHeader?.isSolid
            val solid = mainSolid == true || all.any { it.isSolid }
            val kind = when {
                archive.format == RarFormat.RAR50 -> ArchiveKind.RAR5
                solid -> ArchiveKind.RAR4_SOLID
                else -> ArchiveKind.RAR4
            }
            return RarComicArchive(
                source = file,
                archive = archive,
                kind = kind,
                isSolid = solid,
                mainHeaderSolid = mainSolid,
                pages = pages.map { PageRef(it.name, it.handle.value) },
                entryIndexOfPage = pages.map { it.handle.index }.toIntArray(),
                allHeaders = all,
                progress = progress,
                options = options,
            )
        }

        private fun windowBytes(h: FileHeader): Long =
            if (h.isRar5Family) h.rar5WinSize
            else 0x10000L shl ((h.flags.toInt() and 0x00E0) shr 5).coerceAtMost(6)

        /** Traduce las excepciones de junrar a las nuestras, con mensaje en español. */
        internal fun mapRarException(e: RarException, kind: ArchiveKind, file: File, maxDictionary: Long? = null): ComicArchiveException {
            val chain = generateSequence<Throwable>(e) { it.cause }.take(8).toList()
            fun has(type: Class<out Throwable>) = chain.any { type.isInstance(it) }
            // Ojo: el aviso genérico del constructor dice «maybe file is encrypted, corrupt…»; no cuenta como cifrado.
            val text = chain.mapNotNull { it.message }.joinToString(" ").lowercase(Locale.ROOT)
            return when {
                has(WrongPasswordException::class.java) || has(UnsupportedRarEncryptedException::class.java) ||
                    has(InitDeciphererFailedException::class.java) || "password" in text || "encrypted header" in text ->
                    EncryptedArchiveException(kind, ArchiveTexts.encrypted(file), e)
                has(UnsupportedDictionarySizeException::class.java) ->
                    UnsupportedArchiveException(kind, ArchiveTexts.dictionaryTooBig(file, (maxDictionary ?: 0L) shr 20), e)
                has(MissingNextVolumeException::class.java) || has(MissingPreviousVolumeException::class.java) ->
                    UnsupportedArchiveException(kind, ArchiveTexts.multiVolume(file), e)
                has(UnsupportedRarVersionException::class.java) || has(UnsupportedRarMethodException::class.java) ->
                    UnsupportedArchiveException(kind, ArchiveTexts.unsupportedRar(file, e.message), e)
                has(CrcErrorException::class.java) || has(CorruptHeaderException::class.java) ||
                    has(BadRarArchiveException::class.java) || has(NotRarArchiveException::class.java) ||
                    has(MainHeaderNullException::class.java) ->
                    CorruptArchiveException(kind, ArchiveTexts.corrupt(file, e.message ?: e.javaClass.simpleName), e)
                else -> CorruptArchiveException(kind, ArchiveTexts.corrupt(file, e.message ?: e.javaClass.simpleName), e)
            }
        }
    }
}
