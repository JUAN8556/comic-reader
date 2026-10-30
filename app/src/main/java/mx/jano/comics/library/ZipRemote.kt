package mx.jano.comics.library

import mx.jano.comics.archive.pagesOf
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * La primera página (en orden natural) de un ZIP/CBZ que está en Drive, sin bajarlo entero: los últimos 64 KB
 * (fin del directorio central), el directorio central si no cabía ahí, y la cabecera local + datos de esa entrada.
 * Con ZIP64. Solo métodos 0 (guardado) y 8 (deflate), sin cifrar; si algo no cuadra, null (nunca una excepción
 * por datos raros: es una portada, no merece romper nada).
 */
internal object ZipRemote {

    const val TAIL_BYTES = 64 * 1024
    private const val EOCD_SIG = 0x06054b50
    private const val ZIP64_LOCATOR_SIG = 0x07064b50
    private const val ZIP64_EOCD_SIG = 0x06064b50
    private const val CEN_SIG = 0x02014b50
    private const val LOC_SIG = 0x04034b50
    private const val MAX_CENTRAL_DIRECTORY = 16 * 1024 * 1024
    const val MAX_ENTRY_BYTES = 48 * 1024 * 1024

    class Entry(
        val name: String,
        val flags: Int,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/") || name.endsWith("\\")
        val isEncrypted: Boolean get() = flags and 0x0001 != 0
    }

    /** Bytes de la primera imagen, o null. [size] = tamaño del fichero en Drive. */
    suspend fun firstImage(reader: RangeReader, fileId: String, size: Long): ByteArray? {
        if (size < 22) return null
        val tailStart = maxOf(0L, size - TAIL_BYTES)
        val tail = reader.read(fileId, tailStart, (size - tailStart).toInt())
        val entries = centralDirectory(tail, tailStart, size) { start, length -> reader.read(fileId, start, length) } ?: return null
        val first = firstPage(entries) ?: return null
        return readEntry(first) { start, length -> reader.read(fileId, start, length) }
    }

    /** La primera página en el mismo orden que usa el lector (`archive/PageEntries.kt`). */
    fun firstPage(entries: List<Entry>): Entry? =
        pagesOf(entries, { it.name }, { it.isDirectory }).firstOrNull()?.handle

    /**
     * Entradas del directorio central. [tail] son los bytes del fichero desde [tailStart] hasta el final ([size]);
     * [fetch] trae otros trozos si hace falta.
     */
    suspend fun centralDirectory(
        tail: ByteArray,
        tailStart: Long,
        size: Long,
        fetch: suspend (start: Long, length: Int) -> ByteArray,
    ): List<Entry>? {
        val eocd = findEocd(tail) ?: return null
        var cdSize = u32(tail, eocd + 12)
        var cdOffset = u32(tail, eocd + 16)
        var count = u16(tail, eocd + 10).toLong()
        if (cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || count == 0xFFFFL) {
            val locator = eocd - 20
            if (locator < 0 || s32(tail, locator) != ZIP64_LOCATOR_SIG) return null
            val recordOffset = u64(tail, locator + 8)
            val record = if (recordOffset >= tailStart && recordOffset + 56 <= size) {
                tail.copyOfRange((recordOffset - tailStart).toInt(), minOf(tail.size, (recordOffset - tailStart).toInt() + 56))
            } else {
                fetch(recordOffset, 56)
            }
            if (record.size < 56 || s32(record, 0) != ZIP64_EOCD_SIG) return null
            count = u64(record, 32)
            cdSize = u64(record, 40)
            cdOffset = u64(record, 48)
        }
        if (cdSize <= 0 || cdSize > MAX_CENTRAL_DIRECTORY || cdOffset < 0 || cdOffset + cdSize > size) return null
        val cd = if (cdOffset >= tailStart) {
            val from = (cdOffset - tailStart).toInt()
            tail.copyOfRange(from, minOf(tail.size, from + cdSize.toInt()))
        } else {
            fetch(cdOffset, cdSize.toInt())
        }
        return parseCentralDirectory(cd, count)
    }

    /** Busca el registro de fin de directorio central desde el final (puede llevar un comentario detrás). */
    fun findEocd(tail: ByteArray): Int? {
        var i = tail.size - 22
        while (i >= 0) {
            if (s32(tail, i) == EOCD_SIG) {
                val commentLength = u16(tail, i + 20)
                if (i + 22 + commentLength <= tail.size) return i
            }
            i--
        }
        return null
    }

    fun parseCentralDirectory(cd: ByteArray, expected: Long = -1): List<Entry> {
        val out = ArrayList<Entry>()
        var p = 0
        while (p + 46 <= cd.size && s32(cd, p) == CEN_SIG) {
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            var compressed = u32(cd, p + 20)
            var uncompressed = u32(cd, p + 24)
            val nameLength = u16(cd, p + 28)
            val extraLength = u16(cd, p + 30)
            val commentLength = u16(cd, p + 32)
            var localOffset = u32(cd, p + 42)
            val nameStart = p + 46
            if (nameStart + nameLength + extraLength > cd.size) break
            val name = decodeName(cd, nameStart, nameLength, utf8Flag = flags and 0x0800 != 0)
            // Campo extra ZIP64 (0x0001): solo trae los valores que en la cabecera valen 0xFFFFFFFF, en este orden.
            var e = nameStart + nameLength
            val extraEnd = e + extraLength
            while (e + 4 <= extraEnd) {
                val id = u16(cd, e)
                val len = u16(cd, e + 2)
                if (id == 0x0001) {
                    var q = e + 4
                    if (uncompressed == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { uncompressed = u64(cd, q); q += 8 }
                    if (compressed == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { compressed = u64(cd, q); q += 8 }
                    if (localOffset == 0xFFFFFFFFL && q + 8 <= e + 4 + len) { localOffset = u64(cd, q) }
                }
                e += 4 + len
            }
            out += Entry(name, flags, method, compressed, uncompressed, localOffset)
            p = extraEnd + commentLength
            if (expected >= 0 && out.size.toLong() >= expected) break
        }
        return out
    }

    /** Datos descomprimidos de [entry]; null si está cifrada, usa otro método o no cuadra. */
    suspend fun readEntry(entry: Entry, fetch: suspend (start: Long, length: Int) -> ByteArray): ByteArray? {
        if (entry.isEncrypted || entry.isDirectory) return null
        if (entry.method != 0 && entry.method != 8) return null
        if (entry.compressedSize < 0 || entry.compressedSize > MAX_ENTRY_BYTES) return null
        if (entry.uncompressedSize < 0 || entry.uncompressedSize > MAX_ENTRY_BYTES) return null
        val guess = 30 + entry.name.toByteArray(Charsets.UTF_8).size + 1024 + entry.compressedSize.toInt()
        var chunk = fetch(entry.localHeaderOffset, guess)
        if (chunk.size < 30 || s32(chunk, 0) != LOC_SIG) return null
        val dataStart = 30 + u16(chunk, 26) + u16(chunk, 28)
        val dataEnd = dataStart + entry.compressedSize.toInt()
        if (dataEnd > chunk.size) {
            val more = fetch(entry.localHeaderOffset + chunk.size, dataEnd - chunk.size)
            chunk += more
            if (dataEnd > chunk.size) return null
        }
        val data = chunk.copyOfRange(dataStart, dataEnd)
        return if (entry.method == 0) data else inflate(data, entry.uncompressedSize.toInt())
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(if (expected > 0) expected else data.size * 2)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, n)
                if (out.size() > MAX_ENTRY_BYTES) return null
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private val CP437: Charset? = runCatching { Charset.forName("IBM437") }.getOrNull()

    /** UTF-8 si la bandera lo dice o si los bytes son UTF-8 válido; si no, CP437 (DOS) o Latin-1. */
    private fun decodeName(b: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String {
        if (utf8Flag) return String(b, off, len, Charsets.UTF_8)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(b, off, len)).toString()
        } catch (_: CharacterCodingException) {
        }
        return String(b, off, len, CP437 ?: Charsets.ISO_8859_1)
    }

    private fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun s32(b: ByteArray, i: Int): Int =
        if (i < 0 || i + 4 > b.size) 0
        else (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)
    private fun u32(b: ByteArray, i: Int): Long = s32(b, i).toLong() and 0xFFFFFFFFL
    private fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)
}
