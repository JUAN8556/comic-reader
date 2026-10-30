package mx.jano.comics.archive

import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * CBZ (y los .cbr que en realidad son ZIP) con [ZipFile]: directorio central, acceso aleatorio por entrada
 * y descompresión en streaming. `ZipFile.getInputStream` es seguro entre hilos, así que no hace falta lock.
 */
internal class ZipComicArchive private constructor(
    private val source: File,
    private val zip: ZipFile,
    private val pages: List<PageRef<ZipEntry>>,
    /** Juego de caracteres con el que se leyeron los nombres (UTF-8 o el de DOS, para ZIP viejos). */
    internal val charset: Charset,
) : ComicArchive {

    override val kind: ArchiveKind get() = ArchiveKind.ZIP
    override val pageCount: Int get() = pages.size
    override fun pageName(index: Int): String = pages[index].name

    /** Método de compresión de la primera página (0 = guardado, 8 = deflate). Diagnóstico para el informe. */
    internal val firstPageMethod: Int? get() = pages.firstOrNull()?.handle?.method

    @Volatile private var closed = false

    override fun extractPage(index: Int, dest: File) {
        if (closed) throw IOException(ArchiveTexts.closed(source))
        val page = pages[index]
        val method = page.handle.method
        if (method != ZipEntry.STORED && method != ZipEntry.DEFLATED && method != -1) {
            throw UnsupportedArchiveException(kind, ArchiveTexts.unsupportedZipMethod(source, page.name, method))
        }
        try {
            writeAtomically(dest) { out -> zip.getInputStream(page.handle).use { it.copyToStream(out) } }
        } catch (e: ZipException) {
            throw CorruptArchiveException(kind, ArchiveTexts.corrupt(source, e.message), e)
        }
    }

    override fun close() {
        closed = true
        zip.close()
    }

    companion object {
        /** UTF-8 primero (lo que marca el bit 11 o los ZIP modernos); si los nombres no son UTF-8, CP437 (DOS) y, en último caso, Latin-1. */
        private val CHARSETS: List<Charset> = listOfNotNull(
            Charsets.UTF_8,
            runCatching { Charset.forName("IBM437") }.getOrNull(),
            Charsets.ISO_8859_1,
        )

        fun open(file: File): ZipComicArchive {
            var firstError: Exception? = null
            for (charset in CHARSETS) {
                try {
                    return withPathValidatorRetry { openWith(file, charset) }
                } catch (e: ZipException) {
                    if (firstError == null) firstError = e
                } catch (e: IllegalArgumentException) {
                    // «MALFORMED input»: nombre que no es de este juego de caracteres.
                    if (firstError == null) firstError = e
                }
            }
            throw CorruptArchiveException(ArchiveKind.ZIP, ArchiveTexts.corrupt(file, firstError?.message), firstError)
        }

        private fun openWith(file: File, charset: Charset): ZipComicArchive {
            val zip = ZipFile(file, charset)
            try {
                // Los nombres se decodifican al recorrer las entradas: si el juego de caracteres no vale, falla aquí.
                val pages = pagesOf(zip.entries().asSequence().toList(), { it.name }, { it.isDirectory })
                return ZipComicArchive(file, zip, pages, charset)
            } catch (t: Throwable) {
                zip.close()
                throw t
            }
        }

        /**
         * Android 14+ (apps con targetSdk >= 34) rechaza los ZIP con entradas «../x» o «/x» (ZipPathValidator).
         * Aquí ningún nombre de entrada se usa como ruta de disco (la página va al `dest` que da el lector),
         * así que si salta ese rechazo se desactiva el validador del proceso y se reintenta una vez.
         */
        private inline fun <T> withPathValidatorRetry(block: () -> T): T = try {
            block()
        } catch (e: ZipException) {
            if (e.message?.startsWith("Invalid zip entry path") != true) throw e
            dalvik.system.ZipPathValidator.clearCallback()
            block()
        }
    }
}
