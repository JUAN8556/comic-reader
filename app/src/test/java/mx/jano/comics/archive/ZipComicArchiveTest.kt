package mx.jano.comics.archive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class ZipComicArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun content(name: String) = ("contenido de $name ").repeat(50).toByteArray()

    private fun zip(file: File, names: List<String>, charset: Charset = Charsets.UTF_8, method: Int = ZipEntry.DEFLATED) {
        ZipOutputStream(FileOutputStream(file), charset).use { out ->
            for (name in names) {
                val e = ZipEntry(name)
                if (name.endsWith("/")) {
                    out.putNextEntry(e); out.closeEntry(); continue
                }
                val data = content(name)
                if (method == ZipEntry.STORED) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                out.putNextEntry(e)
                out.write(data)
                out.closeEntry()
            }
        }
    }

    @Test fun orden_natural_y_filtrado() {
        val f = tmp.newFile("muestra.cbz")
        // En el ZIP van en este orden físico, a propósito desordenado.
        zip(f, listOf("10.jpg", "carpeta/", "carpeta/3.png", "2.jpg", "__MACOSX/._1.jpg", ".DS_Store", "Thumbs.db", "leeme.txt", "1.jpg"))
        ComicArchives.open(f).use { a ->
            assertEquals(ArchiveKind.ZIP, a.kind)
            assertEquals(4, a.pageCount)
            assertEquals(listOf("1.jpg", "2.jpg", "10.jpg", "carpeta/3.png"), (0 until a.pageCount).map(a::pageName))
            val out = tmp.newFolder("paginas")
            for (i in 0 until a.pageCount) {
                val dest = File(out, "p$i")
                a.extractPage(i, dest)
                assertArrayEquals(content(a.pageName(i)), dest.readBytes())
            }
            // Sin temporales «.part» olvidados.
            assertEquals(a.pageCount, out.listFiles()!!.size)
        }
    }

    @Test fun cbr_que_es_zip_se_abre_como_zip() {
        val f = tmp.newFile("disfrazado.cbr")
        zip(f, listOf("01.jpg", "02.jpg"), method = ZipEntry.STORED)
        ComicArchives.open(f).use { a ->
            assertEquals(ArchiveKind.ZIP, a.kind)
            assertEquals(2, a.pageCount)
            val dest = tmp.newFile("x")
            a.extractPage(1, dest)
            assertArrayEquals(content("02.jpg"), dest.readBytes())
        }
    }

    @Test fun nombres_en_cp437_sin_bandera_utf8() {
        val cp437 = Charset.forName("IBM437")
        val f = tmp.newFile("dos.cbz")
        zip(f, listOf("Página 10.jpg", "Página 2.jpg"), charset = cp437)
        ComicArchives.open(f).use { a ->
            assertEquals(listOf("Página 2.jpg", "Página 10.jpg"), (0 until a.pageCount).map(a::pageName))
            val dest = tmp.newFile("y")
            a.extractPage(0, dest)
            assertArrayEquals(content("Página 2.jpg"), dest.readBytes())
        }
    }

    @Test fun zip_vacio_tiene_cero_paginas() {
        val f = tmp.newFile("vacio.cbz")
        ZipOutputStream(FileOutputStream(f)).use { } // solo el «fin de directorio central» (PK 05 06)
        assertEquals(ArchiveKind.ZIP, ArchiveSniffer.sniff(f))
        ComicArchives.open(f).use { a -> assertEquals(0, a.pageCount) }
    }

    @Test fun pagina_grande_en_streaming() {
        val f = tmp.newFile("grande.cbz")
        val big = Random(7).nextBytes(12 * 1024 * 1024 + 123)
        ZipOutputStream(FileOutputStream(f)).use { out ->
            out.putNextEntry(ZipEntry("001.jpg")); out.write(big); out.closeEntry()
        }
        ComicArchives.open(f).use { a ->
            val dest = tmp.newFile("big")
            a.extractPage(0, dest)
            assertArrayEquals(big, dest.readBytes())
        }
    }

    @Test fun zip_truncado_da_error_claro() {
        val good = tmp.newFile("bueno.cbz")
        zip(good, listOf("1.jpg", "2.jpg"))
        val bad = tmp.newFile("roto.cbz").apply { writeBytes(good.readBytes().copyOf(40)) }
        val e = runCatching { ComicArchives.open(bad) }.exceptionOrNull()
        assertTrue("$e", e is CorruptArchiveException)
        assertTrue(e!!.message!!, e.message!!.contains("roto.cbz"))
    }
}
