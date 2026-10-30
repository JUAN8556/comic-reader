package mx.jano.comics.archive

import com.github.junrar.Archive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.random.Random

/** junrar de verdad sobre RAR4 escritos por [Rar4StoredWriter] (entradas guardadas, banderas y CRC reales). */
class RarComicArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun page(seed: Int, size: Int = 4096) = Random(seed).nextBytes(size)

    /** Orden físico desordenado, carpeta con «\» de Windows y morralla que no son páginas. */
    private fun sampleEntries(): List<Rar4StoredWriter.Entry> = listOf(
        Rar4StoredWriter.Entry("10.jpg", page(10)),
        Rar4StoredWriter.Entry("leeme.txt", "hola".toByteArray()),
        Rar4StoredWriter.Entry("carpeta", directory = true),
        Rar4StoredWriter.Entry("carpeta\\3.png", page(3)),
        Rar4StoredWriter.Entry("2.jpg", page(2)),
        Rar4StoredWriter.Entry("Thumbs.db", page(99, 100)),
        Rar4StoredWriter.Entry("1.jpg", page(1)),
    )
    private val expectedOrder = listOf("1.jpg", "2.jpg", "10.jpg", "carpeta/3.png")
    private val expectedSeed = listOf(1, 2, 10, 3)

    private fun write(name: String, solid: Boolean, entries: List<Rar4StoredWriter.Entry> = sampleEntries()) =
        tmp.newFile(name).also { Rar4StoredWriter.write(it, entries, solid) }

    @Test fun no_solido_orden_natural_y_contenido_por_los_dos_caminos() {
        val f = write("plano.cbr", solid = false)
        // Lectura directa de lo guardado (lo normal) y junrar a secas (lo que usan las entradas comprimidas).
        for (direct in listOf(true, false)) {
            (ComicArchives.open(f, OpenOptions(directStoredReads = direct)) as RarComicArchive).use { a ->
                assertEquals(ArchiveKind.RAR4, a.kind)
                assertFalse(a.isSolid)
                assertEquals(expectedOrder, (0 until a.pageCount).map(a::pageName))
                val dir = tmp.newFolder("p$direct")
                // Hacia atrás: en un no sólido cada página es independiente.
                for (i in a.pageCount - 1 downTo 0) {
                    val dest = File(dir, "$i")
                    a.extractPage(i, dest)
                    assertArrayEquals(page(expectedSeed[i]), dest.readBytes())
                }
                assertEquals(a.pageCount, dir.listFiles()!!.size) // sin carpeta .solid ni temporales
                assertEquals(if (direct) a.pageCount else 0, a.storedDirectReads)
            }
        }
    }

    @Test fun guardado_directo_detecta_crc_mala() {
        val f = write("crc.cbr", solid = false)
        // Se estropea un byte dentro de los datos de «10.jpg» (la primera entrada, justo tras su cabecera).
        val bytes = f.readBytes()
        val at = 7 + 13 + 32 + "10.jpg".length + 100
        bytes[at] = (bytes[at].toInt() xor 0xFF).toByte()
        f.writeBytes(bytes)
        ComicArchives.open(f).use { a ->
            val e = runCatching { a.extractPage(2, tmp.newFile()) }.exceptionOrNull() // «10.jpg» es la página 2
            assertTrue("$e", e is CorruptArchiveException)
            assertTrue(e!!.message!!, e.message!!.contains("CRC"))
            // Las demás siguen bien.
            val ok = tmp.newFile()
            a.extractPage(0, ok)
            assertArrayEquals(page(1), ok.readBytes())
        }
    }

    /** Salida que acepta [limit] bytes y después falla como un disco lleno (ENOSPC). */
    private class FullDisk(private val target: OutputStream, private val limit: Int) : OutputStream() {
        private var written = 0
        override fun write(b: Int) {
            if (written >= limit) throw IOException("No space left on device")
            target.write(b)
            written++
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (written + len > limit) throw IOException("No space left on device")
            target.write(b, off, len)
            written += len
        }
    }

    @Test fun disco_lleno_dentro_de_junrar_no_se_cuenta_como_archivo_danado() {
        // ARCH-1: junrar envuelve el IOException de la salida en RarException; antes salía «dañado o incompleto».
        val big = 300 * 1024
        val entries = listOf(Rar4StoredWriter.Entry("1.jpg", page(1, big)), Rar4StoredWriter.Entry("2.jpg", page(2, big)))
        for (solid in listOf(false, true)) {
            val f = write("lleno-$solid.cbr", solid, entries)
            (ComicArchives.open(f, OpenOptions(directStoredReads = false)) as RarComicArchive).use { a ->
                a.sinkDecoratorForTests = { FullDisk(it, 1000) }
                val e = runCatching { a.extractPage(1, File(tmp.newFolder(), "p.jpg")) }.exceptionOrNull()
                assertTrue("sólido=$solid: $e", e is IOException)
                assertFalse("sólido=$solid, no es «dañado»: $e", e is ComicArchiveException)
                assertTrue(e!!.message!!, e.message!!.contains("No space left on device"))
                assertTrue(e.message!!, e.message!!.contains("no está dañado"))
                // Con sitio otra vez, la misma página sale entera.
                a.sinkDecoratorForTests = null
                val ok = File(tmp.newFolder(), "ok.jpg")
                a.extractPage(1, ok)
                assertArrayEquals("sólido=$solid", page(2, big), ok.readBytes())
            }
        }
    }

    @Test fun crc_mala_por_junrar_sigue_siendo_archivo_danado() {
        val f = write("crc-junrar.cbr", solid = false)
        val bytes = f.readBytes()
        val at = 7 + 13 + 32 + "10.jpg".length + 100
        bytes[at] = (bytes[at].toInt() xor 0xFF).toByte()
        f.writeBytes(bytes)
        ComicArchives.open(f, OpenOptions(directStoredReads = false)).use { a ->
            val e = runCatching { a.extractPage(2, File(tmp.newFolder(), "x.jpg")) }.exceptionOrNull()
            assertTrue("$e", e is CorruptArchiveException)
        }
    }

    @Test fun cerrado_antes_de_extraer_dice_cerrado() {
        val f = write("cerrado.cbr", solid = true)
        val a = ComicArchives.open(f)
        a.close()
        val e = runCatching { a.extractPage(0, File(tmp.newFolder(), "x.jpg")) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e !is ComicArchiveException)
        assertTrue(e!!.message!!, e.message!!.contains("cerrado"))
    }

    @Test fun el_sniffer_coincide_con_junrar() {
        for (solid in listOf(false, true)) {
            val f = write("s$solid.cbr", solid)
            val junrarSolid = Archive(f).use { it.mainHeader.isSolid }
            assertEquals(solid, junrarSolid)
            assertEquals(if (solid) ArchiveKind.RAR4_SOLID else ArchiveKind.RAR4, ArchiveSniffer.sniff(f))
        }
    }

    @Test fun solido_cualquier_orden_y_cache_que_se_borra_al_cerrar() {
        val f = write("solido.cbr", solid = true)
        val dir = tmp.newFolder("cache")
        val a = ComicArchives.open(f) as RarComicArchive
        assertEquals(ArchiveKind.RAR4_SOLID, a.kind)
        assertTrue(a.isSolid)
        assertEquals(expectedOrder, (0 until a.pageCount).map(a::pageName))
        // Última, primera, del medio, otra vez la última: todo correcto.
        for (i in listOf(3, 0, 2, 1, 3, 0)) {
            val dest = File(dir, "$i.img")
            a.extractPage(i, dest)
            assertArrayEquals("página $i", page(expectedSeed[i]), dest.readBytes())
        }
        // Cada página se pidió al motor una sola vez, aunque se pidieron 6.
        assertEquals(4, a.solidDecompressCalls)
        val staging = dir.listFiles()!!.single { it.name.startsWith(".solid-") }
        assertEquals(4, staging.listFiles()!!.size)
        a.close()
        assertFalse(staging.exists())
        // Las páginas entregadas se quedan, enteras, aunque la caché se borre.
        assertEquals(4, dir.listFiles()!!.size)
        for (i in 0 until 4) assertArrayEquals(page(expectedSeed[i]), File(dir, "$i.img").readBytes())
    }

    @Test fun solido_no_relee_el_archivo_por_pagina() {
        // 40 páginas de 64 KB: medimos los bytes comprimidos que lee junrar al pasar páginas HACIA ATRÁS.
        val n = 40
        val size = 64 * 1024
        val entries = (1..n).map { Rar4StoredWriter.Entry(String.format("%03d.jpg", it), page(it, size)) }
        val f = write("atras.cbr", solid = true, entries = entries)
        val dir = tmp.newFolder("atras")

        // junrar a pelo: cada paso atrás reinicia y relee desde la primera entrada → ~n²/2 entradas.
        val raw = RarComicArchive.PackedProgress()
        Archive(f, com.github.junrar.ArchiveOptions.builder().unrarCallback(raw).build()).use { ar ->
            val headers = ar.fileHeaders
            for (i in n - 1 downTo 0) File(dir, "raw").outputStream().use { ar.extractFile(headers[i], it) }
        }
        val rawEntries = raw.packedRead.toDouble() / size

        // Con la caché de sólidos: cada entrada una vez → ~n entradas.
        val ours = ComicArchives.open(f) as RarComicArchive
        ours.use { a -> for (i in n - 1 downTo 0) a.extractPage(i, File(dir, "p$i")) }
        val ourEntries = ours.packedBytesRead.toDouble() / size

        assertTrue("junrar a pelo leyó $rawEntries entradas", rawEntries > n * n / 2.5)
        assertTrue("con caché se leyeron $ourEntries entradas", ourEntries < n * 1.1)
    }

    @Test fun entrada_cifrada_da_error_claro_sin_pedir_contrasena() {
        val entries = listOf(
            Rar4StoredWriter.Entry("1.jpg", page(1)),
            Rar4StoredWriter.Entry("2.jpg", page(2), encrypted = true),
        )
        val f = write("cifrado.cbr", solid = false, entries = entries)
        val e = runCatching { ComicArchives.open(f) }.exceptionOrNull()
        assertTrue("$e", e is EncryptedArchiveException)
        assertTrue(e!!.message!!, e.message!!.contains("contraseña"))
    }

    @Test fun rar_truncado_da_error_claro() {
        val good = write("bueno.cbr", solid = false)
        val bad = tmp.newFile("truncado.cbr").apply { writeBytes(good.readBytes().copyOf(good.length().toInt() - 3000)) }
        val e = runCatching {
            ComicArchives.open(bad).use { a -> for (i in 0 until a.pageCount) a.extractPage(i, tmp.newFile()) }
        }.exceptionOrNull()
        assertTrue("$e", e is ComicArchiveException)
    }
}
