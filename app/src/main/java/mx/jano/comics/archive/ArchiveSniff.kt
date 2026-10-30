package mx.jano.comics.archive

import java.io.File
import java.io.FileInputStream

/** Bytes de cabecera que se leen para decidir el formato (el «%PDF» puede llegar hasta aquí). */
internal const val SNIFF_BYTES = 1024

private val ZIP_LOCAL = intArrayOf(0x50, 0x4B, 0x03, 0x04)
private val ZIP_EMPTY = intArrayOf(0x50, 0x4B, 0x05, 0x06)
private val ZIP_SPANNED = intArrayOf(0x50, 0x4B, 0x07, 0x08)
private val RAR5_SIG = intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
private val RAR4_SIG = intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
private val SEVEN_ZIP_SIG = intArrayOf(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)
private val PDF_SIG = intArrayOf(0x25, 0x50, 0x44, 0x46) // «%PDF»

/**
 * RAR 1.5-4.x (technote de RAR 4, confirmado con las constantes de junrar 8.1.2 `BaseBlock.MHD_SOLID = 8`):
 * firma de 7 bytes y, justo detrás, el bloque MAIN_HEAD:
 *   byte 7-8  HEAD_CRC (2)
 *   byte 9    HEAD_TYPE (1) = 0x73
 *   byte 10-11 HEAD_FLAGS (2, little-endian); 0x0008 = MHD_SOLID, 0x0080 = MHD_PASSWORD (cabeceras cifradas)
 */
private const val RAR4_MAIN_HEAD_TYPE = 0x73
private const val RAR4_MHD_SOLID = 0x0008

/** Lee la cabecera de [file] y decide el formato. Un fichero vacío o desconocido es [ArchiveKind.UNKNOWN]. */
internal fun sniffFile(file: File): ArchiveKind {
    val head = ByteArray(SNIFF_BYTES)
    var length = 0
    FileInputStream(file).use { input ->
        while (length < head.size) {
            val n = input.read(head, length, head.size - length)
            if (n < 0) break
            length += n
        }
    }
    return sniffHeader(head, length)
}

/** Decide el formato con los primeros [length] bytes de [head]. Separado de la E/S para poder probarlo con arrays. */
internal fun sniffHeader(head: ByteArray, length: Int = head.size): ArchiveKind {
    val len = minOf(length, head.size)
    fun startsWith(sig: IntArray, offset: Int = 0): Boolean {
        if (len < offset + sig.size) return false
        for (i in sig.indices) if ((head[offset + i].toInt() and 0xFF) != sig[i]) return false
        return true
    }
    return when {
        startsWith(ZIP_LOCAL) || startsWith(ZIP_EMPTY) || startsWith(ZIP_SPANNED) -> ArchiveKind.ZIP
        // RAR5 antes que RAR4: comparten los 6 primeros bytes.
        startsWith(RAR5_SIG) -> ArchiveKind.RAR5
        startsWith(RAR4_SIG) -> if (rar4MainHeaderSolid(head, len)) ArchiveKind.RAR4_SOLID else ArchiveKind.RAR4
        startsWith(SEVEN_ZIP_SIG) -> ArchiveKind.SEVEN_ZIP
        (0..minOf(len, SNIFF_BYTES) - PDF_SIG.size).any { startsWith(PDF_SIG, it) } -> ArchiveKind.PDF
        else -> ArchiveKind.UNKNOWN
    }
}

/** Bandera MHD_SOLID del MAIN_HEAD de un RAR4. Si el bloque no está donde toca (o no es 0x73), se da por no sólido. */
private fun rar4MainHeaderSolid(head: ByteArray, len: Int): Boolean {
    if (len < 12) return false
    if ((head[9].toInt() and 0xFF) != RAR4_MAIN_HEAD_TYPE) return false
    val flags = (head[10].toInt() and 0xFF) or ((head[11].toInt() and 0xFF) shl 8)
    return flags and RAR4_MHD_SOLID != 0
}
