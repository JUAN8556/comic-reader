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
import kotlin.random.Random

/** junrar de verdad sobre RAR4 escritos por [Rar4TestWriter] (entradas guardadas, banderas y CRC reales). */
class RarComicArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun page(seed: Int, size: Int = 4096) = Random(seed).nextBytes(size)

    /** Orden físico desordenado, carpeta con «\» de Windows y morralla que no son páginas. */
    private fun sampleEntries(): List<Rar4TestWriter.Entry> = listOf(
        Rar4TestWriter.Entry("10.jpg", page(10)),
        Rar4TestWriter.Entry("leeme.txt", "hola".toByteArray()),
        Rar4TestWriter.Entry("carpeta", directory = true),
        Rar4TestWriter.Entry("carpeta\\3.png", page(3)),
        Rar4TestWriter.Entry("2.jpg", page(2)),
        Rar4TestWriter.Entry("Thumbs.db", page(99, 100)),
        Rar4TestWriter.Entry("1.jpg", page(1)),
    )
    private val expectedOrder = listOf("1.jpg", "2.jpg", "10.jpg", "carpeta/3.png")
    private val expectedSeed = listOf(1, 2, 10, 3)

    private fun write(name: String, solid: Boolean, entries: List<Rar4TestWriter.Entry> = sampleEntries()) =
        tmp.newFile(name).also { Rar4TestWriter.write(it, entries, solid) }

    @Test fun no_solido_orden_natural_y_contenido() {
        val f = write("plano.cbr", solid = false)
        ComicArchives.open(f).use { a ->
            assertEquals(ArchiveKind.RAR4, a.kind)
            assertFalse((a as RarComicArchive).isSolid)
            assertEquals(expectedOrder, (0 until a.pageCount).map(a::pageName))
            val dir = tmp.newFolder("p")
            // Hacia atrás: en un no sólido cada página es independiente.
            for (i in a.pageCount - 1 downTo 0) {
                val dest = File(dir, "$i")
                a.extractPage(i, dest)
                assertArrayEquals(page(expectedSeed[i]), dest.readBytes())
            }
            assertEquals(a.pageCount, dir.listFiles()!!.size) // sin carpeta .solid ni temporales
        }
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
        assertTrue(dir.listFiles()!!.any { it.name.startsWith(".solid-") })
        a.close()
        assertFalse(dir.listFiles()!!.any { it.name.startsWith(".solid-") })
        // Las páginas entregadas se quedan.
        assertEquals(4, dir.listFiles()!!.size)
    }

    @Test fun solido_no_relee_el_archivo_por_pagina() {
        // 40 páginas de 64 KB: medimos los bytes comprimidos que lee junrar al pasar páginas HACIA ATRÁS.
        val n = 40
        val size = 64 * 1024
        val entries = (1..n).map { Rar4TestWriter.Entry(String.format("%03d.jpg", it), page(it, size)) }
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
            Rar4TestWriter.Entry("1.jpg", page(1)),
            Rar4TestWriter.Entry("2.jpg", page(2), encrypted = true),
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
