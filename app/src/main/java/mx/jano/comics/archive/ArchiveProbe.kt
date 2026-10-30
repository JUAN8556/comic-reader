package mx.jano.comics.archive

import com.github.junrar.RarFormat
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/**
 * Diagnóstico del motor para la sonda (no es contrato): qué dice junrar del archivo y por qué camino salió cada
 * página. Sin esto, los ms de «Abrir archivo» no distinguen una página que pasó por junrar de una guardada que se
 * copió directamente, ni un RAR5 sólido de uno que no lo es ([ArchiveKind] no tiene RAR5_SOLID).
 */
data class ExtractionCounters(
    /** Páginas guardadas copiadas sin junrar (RAR no sólido). */
    val directReads: Int = 0,
    /** Entradas que la caché de sólidos pidió al motor. */
    val solidDecompressCalls: Int = 0,
    /** Bytes comprimidos que pasaron por junrar o por la lectura directa. */
    val packedBytesRead: Long = 0,
)

/** Contadores acumulados desde que se abrió; en ZIP y PDF, todo a cero. */
fun ComicArchive.extractionCounters(): ExtractionCounters = when (this) {
    is RarComicArchive -> ExtractionCounters(storedDirectReads, solidDecompressCalls, packedBytesRead)
    else -> ExtractionCounters()
}

/** Sólido según junrar (solo RAR); null en los demás formatos. */
fun ComicArchive.solidAccordingToEngine(): Boolean? = (this as? RarComicArchive)?.isSolid

/** Una línea con lo que el motor sabe del archivo. */
fun ComicArchive.engineDiagnostics(): String = when (this) {
    is RarComicArchive -> buildString {
        append("junrar 8.1.2 · formato ").append(format?.name ?: "?")
        append(" · sólido ").append(yesNo(isSolid))
        append(" (MHD_SOLID ").append(mainHeaderSolid?.let(::yesNo) ?: "—, RAR5 no lo tiene").append(")")
        append(" · método de la 1.ª página ").append(ArchiveProbeFormat.rarMethod(format == RarFormat.RAR50, firstPageMethod))
        append(" · ventana máx. ").append(maxWindowBytes / 1024).append(" KB")
        append(" · cabeceras rotas ").append(brokenHeaders)
        append(" · páginas guardadas leídas sin junrar ").append(storedDirectReads)
        append(" · bytes comprimidos leídos ").append(packedBytesRead)
        if (isSolid) append(" · entradas pedidas al motor por la caché de sólidos ").append(solidDecompressCalls)
    }
    is ZipComicArchive -> "java.util.zip · nombres en ${charset.name()} · método de la 1.ª página " +
        ArchiveProbeFormat.zipMethod(firstPageMethod)
    is PdfComicArchive -> "PdfRenderer de la plataforma · cada página a PNG de $targetWidthPx px de ancho"
    else -> kind.name
}

/** Por qué camino salió una página, comparando los contadores de antes y de después de extraerla. */
fun ComicArchive.describeExtraction(before: ExtractionCounters, after: ExtractionCounters): String =
    ArchiveProbeFormat.via(kind, solidAccordingToEngine(), before, after)

/** Formato real de un fichero por su cabecera, con lo que dice de sólido (incluido RAR5). Para elegir muestras. */
object ArchiveHeaders {
    /** Bytes que hacen falta: los mismos que lee el sniffer. */
    const val HEAD_BYTES = SNIFF_BYTES

    fun describe(head: ByteArray, length: Int = head.size): String {
        val len = minOf(length, head.size)
        return when (sniffHeader(head, len)) {
            ArchiveKind.ZIP -> "ZIP"
            ArchiveKind.RAR4 -> "RAR4"
            ArchiveKind.RAR4_SOLID -> "RAR4 sólido"
            ArchiveKind.RAR5 -> when (rar5ArchiveSolid(head, len)) {
                true -> "RAR5 sólido"
                false -> "RAR5"
                null -> "RAR5 (cabecera principal ilegible: ¿cifrada?)"
            }
            ArchiveKind.SEVEN_ZIP -> "7z"
            ArchiveKind.PDF -> "PDF"
            ArchiveKind.UNKNOWN -> "desconocido"
        }
    }

    fun describe(file: File): String {
        val head = ByteArray(HEAD_BYTES)
        var n = 0
        FileInputStream(file).use { input ->
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
        }
        return describe(head, n)
    }
}

/**
 * Bandera de sólido de un RAR5 (technote de RAR 5.0): tras la firma de 8 bytes va el bloque principal:
 * CRC32 (4) · tamaño (vint) · tipo (vint) = 1 · banderas del bloque (vint) · [tamaño del área extra (vint) si 0x0001]
 * · [tamaño de datos (vint) si 0x0002] · banderas del archivo (vint), donde 0x0004 = sólido.
 * null si el primer bloque no es el principal (p. ej. tipo 4, cabeceras cifradas) o no cabe en [len].
 */
internal fun rar5ArchiveSolid(head: ByteArray, len: Int): Boolean? {
    var p = 8 + 4
    fun vint(): Long? {
        var result = 0L
        var shift = 0
        while (p < len && shift < 64) {
            val b = head[p++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        return null
    }
    vint() ?: return null // tamaño del bloque
    if (vint() != 1L) return null // tipo: 1 = bloque principal
    val blockFlags = vint() ?: return null
    if (blockFlags and 0x0001L != 0L) vint() ?: return null
    if (blockFlags and 0x0002L != 0L) vint() ?: return null
    val archiveFlags = vint() ?: return null
    return archiveFlags and 0x0004L != 0L
}

/** Textos puros del diagnóstico (probados en la JVM). */
internal object ArchiveProbeFormat {
    private val RAR4_METHODS = mapOf(
        0x30 to "guardado", 0x31 to "el más rápido", 0x32 to "rápido",
        0x33 to "normal", 0x34 to "bueno", 0x35 to "máximo",
    )

    fun rarMethod(rar5: Boolean, method: Int?): String = when {
        method == null -> "?"
        rar5 -> if (method == 0) "0 (guardado)" else "$method (comprimido)"
        else -> String.format(Locale.ROOT, "0x%02x", method) + (RAR4_METHODS[method]?.let { " ($it)" } ?: "")
    }

    fun zipMethod(method: Int?): String = when (method) {
        null -> "?"
        0 -> "0 (guardado)"
        8 -> "8 (deflate)"
        else -> "$method"
    }

    fun via(kind: ArchiveKind, solid: Boolean?, before: ExtractionCounters, after: ExtractionCounters): String = when (kind) {
        ArchiveKind.PDF -> "PdfRenderer → PNG"
        ArchiveKind.ZIP -> "java.util.zip"
        ArchiveKind.RAR4, ArchiveKind.RAR4_SOLID, ArchiveKind.RAR5 -> when {
            after.directReads > before.directReads -> "lectura directa (guardada, sin junrar)"
            solid == true -> {
                val calls = after.solidDecompressCalls - before.solidDecompressCalls
                if (calls == 0) "caché de sólidos (acierto: el motor no descomprimió nada)"
                else "junrar sólido: $calls entradas descomprimidas para llegar a ella"
            }
            else -> "junrar"
        } + " · ${(after.packedBytesRead - before.packedBytesRead).coerceAtLeast(0) / 1024} KB comprimidos leídos"
        else -> kind.name
    }
}

private fun yesNo(b: Boolean) = if (b) "sí" else "no"
