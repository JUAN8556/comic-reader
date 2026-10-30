package mx.jano.comics.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import mx.jano.comics.archive.Rar4StoredWriter
import mx.jano.comics.drive.DriveAbout
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.metadata.IndiceSync
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class CoverStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Drive falso para rangos: id → bytes. Apunta cada lectura (inicio, longitud) y la concurrencia. */
    private class FakeRanges(val files: MutableMap<String, ByteArray> = HashMap()) : RangeReader {
        val reads = Collections.synchronizedList(mutableListOf<Pair<Long, Int>>())
        val readsById = Collections.synchronizedList(mutableListOf<String>())
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        var delayMs = 0L

        override suspend fun read(fileId: String, start: Long, length: Int): ByteArray {
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            try {
                if (delayMs > 0) delay(delayMs)
                reads += start to length
                readsById += fileId
                val data = files[fileId] ?: throw java.io.IOException("404 $fileId")
                if (start >= data.size) return ByteArray(0)
                return data.copyOfRange(start.toInt(), minOf(data.size.toLong(), start + length).toInt())
            } finally {
                concurrent.decrementAndGet()
            }
        }

        val bytesRead: Long get() = synchronized(reads) { reads.sumOf { it.second.toLong() } }
    }

    /** «Codificador» de pruebas: guarda los bytes tal cual (en la app es WebP con Bitmap). */
    private val copyEncoder = CoverEncoder { image, dest -> dest.writeBytes(image); true }

    private fun store(ranges: RangeReader?, indice: IndiceSync? = null, encoder: CoverEncoder = copyEncoder) = DriveCoverStore(
        coversDir = File(tmp.root, "files/covers"),
        workDir = File(tmp.root, "cache/portadas"),
        ranges = ranges,
        indice = indice,
        encoder = encoder,
        log = { println("[portadas] $it") },
    )

    private fun page(seed: Int, size: Int) = Random(seed).nextBytes(size)

    private fun zipOf(vararg entries: Pair<String, ByteArray>, stored: Boolean = false, comment: String? = null): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            comment?.let { z.setComment(it) }
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun rarOf(entries: List<Rar4StoredWriter.Entry>, solid: Boolean): ByteArray {
        val bos = ByteArrayOutputStream()
        Rar4StoredWriter.write(bos, entries, solid)
        return bos.toByteArray()
    }

    private fun md5Of(text: String) = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun book(id: String, bytes: ByteArray, md5: String? = md5Of(id)) =
        Book(id = id, name = "$id.cbr", folderId = "F", path = "Serie/$id.cbr", size = bytes.size.toLong(), md5 = md5)

    @Test fun zip_primera_pagina_en_orden_natural_sin_bajarlo_entero() = runBlocking {
        val first = page(1, 50_000)
        val zip = zipOf(
            "ComicInfo.xml" to "<x/>".toByteArray(),
            "__MACOSX/._1.jpg" to page(9, 100),
            "10.jpg" to page(10, 400_000),
            "2.jpg" to page(2, 400_000),
            "1.jpg" to first,
            comment = "comentario del zip",
        )
        val ranges = FakeRanges(mutableMapOf("z" to zip))
        val b = book("z", zip)
        val s = store(ranges)
        val cover = s.ensureCover(b)
        assertNotNull(cover)
        assertArrayEquals(first, cover!!.readBytes())
        assertEquals(File(tmp.root, "files/covers/${b.md5}.webp"), cover)
        assertTrue("leídos ${ranges.bytesRead} de ${zip.size}", ranges.bytesRead < zip.size / 2)
        // Ya en disco: no vuelve a Drive.
        val before = ranges.reads.size
        assertEquals(cover, s.ensureCover(b))
        assertEquals(cover, s.coverFile(b))
        assertEquals(before, ranges.reads.size)
    }

    @Test fun zip_guardado_y_cbr_que_en_realidad_es_zip() = runBlocking {
        val first = page(3, 20_000)
        val zip = zipOf("Serie/002.png" to page(4, 1000), "Serie/001.png" to first, stored = true)
        val b = book("disfrazado", zip).copy(name = "Disfrazado.cbr")
        assertArrayEquals(first, store(FakeRanges(mutableMapOf("disfrazado" to zip))).ensureCover(b)!!.readBytes())
    }

    @Test fun rar_por_prefijo_creciente_sin_bajarlo_entero() = runBlocking {
        val first = page(1, 1_500_000)  // no cabe en 1 MB: hace falta el paso de 2 MB
        val entries = listOf(
            Rar4StoredWriter.Entry("ComicInfo.xml", "<x/>".toByteArray()),
            Rar4StoredWriter.Entry("001.jpg", first),
            Rar4StoredWriter.Entry("002.jpg", page(2, 1_500_000)),
            Rar4StoredWriter.Entry("003.jpg", page(3, 1_500_000)),
            Rar4StoredWriter.Entry("004.jpg", page(4, 1_500_000)),
        )
        for (solid in listOf(false, true)) {
            val rar = rarOf(entries, solid)
            val ranges = FakeRanges(mutableMapOf("r" to rar))
            val b = book("r", rar, md5 = if (solid) "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" else "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
            val cover = store(ranges).ensureCover(b)
            assertNotNull("sólido=$solid", cover)
            assertArrayEquals(first, cover!!.readBytes())
            // cabecera (1 KB) + 1 MB + 1 MB más; nunca el fichero entero (6 MB).
            assertEquals("sólido=$solid", listOf(0L to 1024, 0L to (1 shl 20), (1L shl 20) to (1 shl 20)), ranges.reads.toList())
            assertTrue(ranges.bytesRead < rar.size / 2)
        }
        // La carpeta de trabajo queda limpia.
        assertTrue(File(tmp.root, "cache/portadas").listFiles().orEmpty().none { it.name.startsWith("rar-") })
    }

    @Test fun siete_zip_y_pdf_no_se_bajan() = runBlocking {
        val sevenZip = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C) + ByteArray(5000)
        val pdf = "%PDF-1.7\n".toByteArray() + ByteArray(5000)
        val ranges = FakeRanges(mutableMapOf("7z" to sevenZip, "pdf" to pdf))
        val s = store(ranges)
        assertNull(s.ensureCover(book("7z", sevenZip)))
        assertNull(s.ensureCover(book("pdf", pdf)))
        assertEquals(listOf(0L to 1024, 0L to 1024), ranges.reads.toList())
    }

    @Test fun fallo_se_recuerda_y_no_se_repite() = runBlocking {
        val garbage = ByteArray(3000) { 1 }
        val ranges = FakeRanges(mutableMapOf("g" to garbage))
        val s = store(ranges)
        assertNull(s.ensureCover(book("g", garbage)))
        val after = ranges.reads.size
        assertNull(s.ensureCover(book("g", garbage)))
        assertEquals(after, ranges.reads.size)
    }

    @Test fun error_de_red_da_null_sin_excepcion() = runBlocking {
        val s = store(FakeRanges())
        assertNull(s.ensureCover(book("noexiste", ByteArray(10_000))))
    }

    @Test fun nunca_mas_de_tres_a_la_vez_y_la_misma_una_sola_vez() = runBlocking {
        val first = page(1, 2000)
        val zip = zipOf("1.jpg" to first)
        val ranges = FakeRanges().apply { delayMs = 20 }
        val books = (1..10).map { i -> book("b$i", zip, md5 = String.format("%032x", i)).also { ranges.files[it.id] = zip } }
        val s = store(ranges)
        val results = books.map { b -> async(kotlinx.coroutines.Dispatchers.IO) { s.ensureCover(b) } } +
            (1..5).map { async(kotlinx.coroutines.Dispatchers.IO) { s.ensureCover(books[0]) } }
        val files = results.awaitAll()
        assertTrue(files.all { it != null })
        assertTrue("máximo ${ranges.maxConcurrent.get()}", ranges.maxConcurrent.get() <= DriveCoverStore.MAX_PARALLEL)
        // b1 se pidió 6 veces a la vez y se leyó lo mismo que b2, que se pidió una.
        assertTrue(ranges.readsById.count { it == "b2" } > 0)
        assertEquals(ranges.readsById.count { it == "b2" }, ranges.readsById.count { it == "b1" })
    }

    @Test fun sin_md5_usa_el_id_saneado() = runBlocking {
        val zip = zipOf("1.jpg" to page(1, 100))
        val b = book("id/raro:1", zip, md5 = null)
        val cover = store(FakeRanges(mutableMapOf(b.id to zip))).ensureCover(b)
        assertEquals("id_id_raro_1.webp", cover!!.name)
    }

    @Test fun portada_desde_el_archivo_local() = runBlocking {
        val first = page(5, 3000)
        val file = File(tmp.root, "libro.cbz").apply { writeBytes(zipOf("b.jpg" to page(6, 10), "a.jpg" to first)) }
        val b = book("loc", file.readBytes())
        val s = store(null)
        assertNull(s.ensureCover(b))          // sin Drive no hay prefijo
        val cover = s.coverFromLocalFile(b, file)
        assertArrayEquals(first, cover!!.readBytes())
        assertEquals(cover, s.coverFile(b))
    }

    @Test fun el_codificador_que_falla_no_deja_nada() = runBlocking {
        val zip = zipOf("1.jpg" to page(1, 100))
        val b = book("x", zip)
        val s = store(FakeRanges(mutableMapOf("x" to zip)), encoder = { _, _ -> false })
        assertNull(s.ensureCover(b))
        assertNull(s.coverFile(b))
    }

    // ------------------------------------------------------------------ portadas.zip del índice

    private class FakeDrive(val tree: Map<String, List<DriveFile>>, val content: Map<String, ByteArray>) : DriveApi {
        val downloads = mutableListOf<String>()
        override suspend fun findFolder(name: String, parentId: String): DriveFile? = null
        override suspend fun listChildren(folderId: String) = tree[folderId].orEmpty()
        override suspend fun download(fileId: String, dest: File, resume: Boolean, onProgress: (Long, Long?) -> Unit) {
            downloads += fileId
            dest.parentFile?.mkdirs()
            dest.writeBytes(content.getValue(fileId))
        }
        override suspend fun about() = DriveAbout()
    }

    @Test fun importa_portadas_zip_por_md5_y_por_indice() = runBlocking {
        val md5a = "a".repeat(32)
        val md5b = "b".repeat(32)
        val md5c = "c".repeat(32)
        val portadas = zipOf(
            "portadas/$md5a.webp" to "A".toByteArray(),
            "portadas/$md5b.webp" to "B".toByteArray(),
            "otras/especial.webp" to "C".toByteArray(),
            "otras/sin-indice.webp" to "?".toByteArray(),
            "portadas/" to ByteArray(0),
        )
        val indiceJsonl = """{"ruta":"X/c.cbr","md5":"$md5c","portada":"otras/especial.webp"}""" + "\n"
        val folder = DriveFile("IDX", ".indice", "application/vnd.google-apps.folder")
        fun drive(md5: String) = FakeDrive(
            tree = mapOf(
                "ROOT" to listOf(folder),
                "IDX" to listOf(
                    DriveFile("P", "portadas.zip", "application/zip", portadas.size.toLong(), md5),
                    DriveFile("I", "indice.jsonl", "application/json", indiceJsonl.length.toLong(), "m-ind"),
                ),
            ),
            content = mapOf("P" to portadas, "I" to indiceJsonl.toByteArray()),
        )
        val d1 = drive("zip-v1")
        val sync1 = IndiceSync(File(tmp.root, "files/indice"), d1, rootId = { "ROOT" })
        val s = store(null, sync1)
        assertEquals(3, s.importIndexCovers())
        assertEquals("A", File(tmp.root, "files/covers/$md5a.webp").readText())
        assertEquals("C", File(tmp.root, "files/covers/$md5c.webp").readText())
        assertEquals(listOf("I", "P"), d1.downloads)
        assertTrue(!File(tmp.root, "cache/portadas/portadas.zip").exists())
        val bookA = Book("idA", "a.cbr", "F", "X/a.cbr", 10, md5 = md5a.uppercase())
        assertNotNull(s.coverFile(bookA))

        // Mismo md5: no se vuelve a bajar.
        assertEquals(0, s.importIndexCovers())
        assertEquals(listOf("I", "P"), d1.downloads)

        // portadas.zip nuevo: se baja otra vez (indice.jsonl no cambió).
        val d2 = drive("zip-v2")
        val s2 = store(null, IndiceSync(File(tmp.root, "files/indice"), d2, rootId = { "ROOT" }))
        assertEquals(3, s2.importIndexCovers())
        assertEquals(listOf("P"), d2.downloads)
    }

    @Test fun sin_indice_en_drive_importa_cero() = runBlocking {
        val drive = FakeDrive(tree = mapOf("ROOT" to emptyList()), content = emptyMap())
        assertEquals(0, store(null, IndiceSync(File(tmp.root, "i"), drive, rootId = { "ROOT" })).importIndexCovers())
        assertEquals(0, store(null, IndiceSync(File(tmp.root, "i"), drive, rootId = { null })).importIndexCovers())
        assertEquals(0, store(null, null).importIndexCovers())
    }
}
