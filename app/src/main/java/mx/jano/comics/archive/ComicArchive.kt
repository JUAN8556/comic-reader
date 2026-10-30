package mx.jano.comics.archive

import java.io.Closeable
import java.io.File

/** CONTRATO COMÚN. Un cómic empaquetado (CBZ/CBR/CB7/PDF) visto como lista ordenada de páginas. */
enum class ArchiveKind { ZIP, RAR4, RAR4_SOLID, RAR5, SEVEN_ZIP, PDF, UNKNOWN }

interface ComicArchive : Closeable {
    val kind: ArchiveKind
    val pageCount: Int
    fun pageName(index: Int): String

    /**
     * Extrae la página [index] a [dest] (JPEG/PNG/WebP tal cual; en PDF, un JPEG renderizado al ancho de pantalla).
     * En un RAR4 sólido NO debe releer el archivo entero por cada página (O(n²)); junrar ≥ 7.6 da acceso aleatorio.
     */
    fun extractPage(index: Int, dest: File)
}

object ArchiveSniffer {
    /** Decide por los bytes de cabecera, nunca por la extensión (hay ZIP y 7z disfrazados de .cbr). */
    fun sniff(file: File): ArchiveKind = sniffFile(file)
}

object ComicArchives {
    /** Abre con el motor que toque. Páginas = solo imágenes, en orden natural (1, 2, 10; no 1, 10, 2). */
    fun open(file: File): ComicArchive = openComicArchive(file, OpenOptions())
}
