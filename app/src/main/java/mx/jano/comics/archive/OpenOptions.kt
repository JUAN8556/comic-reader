package mx.jano.comics.archive

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Tamaño de los búferes de copia: nunca se carga una entrada entera en memoria. */
internal const val COPY_BUFFER = 64 * 1024

/**
 * Ajustes de apertura. [ComicArchives.open] (el del contrato) usa los de por defecto.
 *
 * @property pdfTargetWidthPx ancho del PNG al que se renderiza cada página de un PDF.
 * @property solidStagingParent dónde dejar la caché de un RAR sólido; `null` = junto al `dest` de la primera página
 *   pedida, en una carpeta oculta `.solid-<huella>` que se borra al cerrar.
 * @property maxRarDictionaryBytes diccionario RAR máximo que se acepta; `null` = la mitad del heap de la app
 *   (en la tablet, unos 128-256 MB). Por encima se lanza [UnsupportedArchiveException] en vez de un OutOfMemoryError.
 *   El mismo tope vale para la memoria de PPMd en RAR 2.9 (junrar lo comprueba con este valor).
 * @property directStoredReads leer sin junrar las páginas guardadas de los RAR no sólidos (ver [RarComicArchive]);
 *   solo se apaga en pruebas, para ejercitar también el camino de junrar.
 */
data class OpenOptions(
    val pdfTargetWidthPx: Int = 2000,
    val solidStagingParent: File? = null,
    val maxRarDictionaryBytes: Long? = null,
    internal val directStoredReads: Boolean = true,
) {
    init {
        require(pdfTargetWidthPx in 16..16_384) { "pdfTargetWidthPx fuera de rango: $pdfTargetWidthPx" }
    }

    internal fun effectiveMaxDictionary(): Long =
        maxRarDictionaryBytes ?: (Runtime.getRuntime().maxMemory() / 2).coerceIn(32L shl 20, 4L shl 30)
}

/** Igual que [ComicArchives.open] pero con ajustes. */
fun ComicArchives.open(file: File, options: OpenOptions): ComicArchive = openComicArchive(file, options)

internal fun openComicArchive(file: File, options: OpenOptions): ComicArchive {
    return when (val kind = sniffFile(file)) {
        ArchiveKind.ZIP -> ZipComicArchive.open(file)
        ArchiveKind.RAR4, ArchiveKind.RAR4_SOLID, ArchiveKind.RAR5 -> RarComicArchive.open(file, kind, options)
        ArchiveKind.PDF -> PdfComicArchive.open(file, options.pdfTargetWidthPx)
        ArchiveKind.SEVEN_ZIP -> throw UnsupportedArchiveException(kind, ArchiveTexts.sevenZip(file))
        ArchiveKind.UNKNOWN -> throw UnsupportedArchiveException(kind, ArchiveTexts.unknown(file))
    }
}

/**
 * Escribe [dest] de forma atómica: primero a un temporal en la misma carpeta y luego `rename`.
 * Quien lea [dest] nunca ve una página a medias; si [block] falla, [dest] queda como estaba.
 */
internal inline fun writeAtomically(dest: File, block: (OutputStream) -> Unit) {
    val dir = dest.absoluteFile.parentFile ?: throw IOException("Sin carpeta para $dest")
    if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("No se pudo crear $dir")
    val tmp = File.createTempFile(".${dest.name}.", ".part", dir)
    try {
        BufferedOutputStream(FileOutputStream(tmp), COPY_BUFFER).use { block(it) }
        Files.move(tmp.toPath(), dest.absoluteFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally {
        if (tmp.exists()) tmp.delete()
    }
}

internal fun InputStream.copyToStream(out: OutputStream): Long = copyTo(out, COPY_BUFFER)
