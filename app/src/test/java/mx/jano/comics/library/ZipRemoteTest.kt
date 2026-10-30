package mx.jano.comics.library

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipRemoteTest {

    private class Reader(val data: ByteArray) : RangeReader {
        var requests = 0
        var bytes = 0L
        override suspend fun read(fileId: String, start: Long, length: Int): ByteArray {
            requests++
            val end = minOf(data.size.toLong(), start + length).toInt()
            if (start >= data.size) return ByteArray(0)
            bytes += end - start
            return data.copyOfRange(start.toInt(), end)
        }
    }

    private fun zip(entries: Sequence<Pair<String, ByteArray>>, utf8: Boolean = true): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos, if (utf8) Charsets.UTF_8 else charset("IBM437")).use { z ->
            for ((name, data) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test fun directorio_central_fuera_de_los_ultimos_64_kb() = runBlocking {
        // 3000 entradas con nombres largos: el directorio central pasa de 64 KB y hay que pedirlo aparte.
        val first = Random(1).nextBytes(5000)
        val names = (2..3000).map { String.format("carpeta con un nombre bastante largo/página %05d.jpg", it) }
        val data = zip(
            names.asSequence().map { it to ByteArray(10) } + sequenceOf("carpeta con un nombre bastante largo/página 00001.jpg" to first),
        )
        val reader = Reader(data)
        assertArrayEquals(first, ZipRemote.firstImage(reader, "f", data.size.toLong()))
        // Cola, directorio central y cabecera local + datos (una más si la cabecera local trae un extra grande).
        assertTrue("${reader.requests} peticiones", reader.requests in 3..4)
        assertTrue(reader.bytes < data.size)
    }

    @Test fun zip64_con_mas_de_65535_entradas() = runBlocking {
        val first = "PORTADA".toByteArray()
        val data = zip(
            (1..65_600).asSequence().map { String.format("p%06d.jpg", it + 1) to byteArrayOf(1) } + sequenceOf("p000001.jpg" to first),
        )
        val tail = data.copyOfRange(data.size - ZipRemote.TAIL_BYTES, data.size)
        val eocd = ZipRemote.findEocd(tail)!!
        // El EOCD normal dice 0xFFFF entradas: obliga al registro ZIP64.
        assertEquals(0xFFFF, (tail[eocd + 10].toInt() and 0xFF) or ((tail[eocd + 11].toInt() and 0xFF) shl 8))
        assertArrayEquals(first, ZipRemote.firstImage(Reader(data), "f", data.size.toLong()))
    }

    @Test fun nombres_cp437_de_zip_viejos() = runBlocking {
        val first = "uno".toByteArray()
        val data = zip(sequenceOf("Pág 2.jpg" to "dos".toByteArray(), "Pág 1.jpg" to first), utf8 = false)
        assertArrayEquals(first, ZipRemote.firstImage(Reader(data), "f", data.size.toLong()))
    }

    @Test fun sin_imagenes_o_datos_rotos_da_null() = runBlocking {
        val noImages = zip(sequenceOf("leeme.txt" to "hola".toByteArray(), "ComicInfo.xml" to "<x/>".toByteArray()))
        assertNull(ZipRemote.firstImage(Reader(noImages), "f", noImages.size.toLong()))
        val garbage = Random(3).nextBytes(100_000)
        assertNull(ZipRemote.firstImage(Reader(garbage), "f", garbage.size.toLong()))
        assertNull(ZipRemote.firstImage(Reader(ByteArray(10)), "f", 10))
        // Truncado por el final: no hay EOCD.
        val good = zip(sequenceOf("1.jpg" to ByteArray(1000)))
        val cut = good.copyOf(good.size - 10)
        assertNull(ZipRemote.firstImage(Reader(cut), "f", cut.size.toLong()))
    }

    @Test fun cifrado_o_metodo_raro_da_null() = runBlocking {
        val entry = ZipRemote.Entry("1.jpg", flags = 1, method = 8, compressedSize = 10, uncompressedSize = 10, localHeaderOffset = 0)
        assertNull(ZipRemote.readEntry(entry) { _, _ -> ByteArray(100) })
        val bzip = ZipRemote.Entry("1.jpg", flags = 0, method = 12, compressedSize = 10, uncompressedSize = 10, localHeaderOffset = 0)
        assertNull(ZipRemote.readEntry(bzip) { _, _ -> ByteArray(100) })
    }
}
