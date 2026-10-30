package mx.jano.comics.archive

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.CRC32

/**
 * Escribe RAR 2.9 (formato RAR4) con las entradas GUARDADAS (método 0x30, sin comprimir), según la technote de RAR 4.
 * Sirve para probar junrar sin ficheros binarios en el repo y sin el `rar` propietario: el formato de bloques,
 * las CRC y las banderas (MHD_SOLID, LHD_SOLID, LHD_PASSWORD) son los reales; lo único sintético es que no comprime.
 * La CRC16 de cabecera se comprobó contra un RAR real (0x90CF del MAIN_HEAD vacío) y el bloque final (0x3DC4).
 *
 * Vive en `main` (no en `test`) porque la sonda genera con él un RAR4 sólido para la tablet («Sólido sintético»):
 * en la biblioteca no hay ninguno. Las entradas se escriben una a una, así que se pueden generar al vuelo.
 */
internal object Rar4StoredWriter {
    class Entry(
        val name: String,
        val data: ByteArray = ByteArray(0),
        val directory: Boolean = false,
        val encrypted: Boolean = false,
    )

    private const val MHD_SOLID = 0x0008
    private const val LHD_PASSWORD = 0x0004
    private const val LHD_SOLID = 0x0010
    private const val LHD_DIRECTORY = 0x00E0
    private const val LONG_BLOCK = 0x8000

    fun write(file: File, entries: Iterable<Entry>, solid: Boolean) {
        BufferedOutputStream(FileOutputStream(file), 64 * 1024).use { write(it, entries, solid) }
    }

    fun write(out: OutputStream, entries: Iterable<Entry>, solid: Boolean) {
        out.write(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        // MAIN_HEAD: HighPosAV(2) + PosAV(4)
        block(out, type = 0x73, flags = if (solid) MHD_SOLID else 0, body = ByteArray(6))
        entries.forEachIndexed { i, e ->
            var flags = LONG_BLOCK
            if (solid && i > 0) flags = flags or LHD_SOLID
            if (e.encrypted) flags = flags or LHD_PASSWORD
            if (e.directory) flags = flags or LHD_DIRECTORY
            val name = e.name.toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(e.data) }.value
            val body = ByteArrayOutputStream()
            body.le32(e.data.size.toLong()) // PACK_SIZE
            body.le32(e.data.size.toLong()) // UNP_SIZE
            body.write(2) // HOST_OS = Win32
            body.le32(crc) // FILE_CRC
            body.le32(0x5D5D6000L) // FTIME (DOS): 2026-10-29 12:00
            body.write(29) // UNP_VER = 2.9
            body.write(0x30) // METHOD = guardado
            body.le16(name.size) // NAME_SIZE
            body.le32(if (e.directory) 0x10L else 0x20L) // ATTR
            body.write(name)
            block(out, type = 0x74, flags = flags, body = body.toByteArray())
            out.write(e.data)
        }
        block(out, type = 0x7B, flags = 0x4000, body = ByteArray(0)) // ENDARC_HEAD
    }

    private fun block(out: OutputStream, type: Int, flags: Int, body: ByteArray) {
        val size = 7 + body.size
        val head = ByteArrayOutputStream()
        head.write(type)
        head.le16(flags)
        head.le16(size)
        head.write(body)
        val bytes = head.toByteArray()
        val crc = CRC32().apply { update(bytes) }.value and 0xFFFF
        out.write((crc and 0xFF).toInt())
        out.write(((crc shr 8) and 0xFF).toInt())
        out.write(bytes)
    }

    private fun ByteArrayOutputStream.le16(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.le32(v: Long) {
        for (i in 0 until 4) write(((v shr (8 * i)) and 0xFF).toInt())
    }
}
