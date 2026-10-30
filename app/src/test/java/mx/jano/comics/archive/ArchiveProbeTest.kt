package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class ArchiveProbeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val rar5Sig = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)

    /** Firma RAR5 + CRC32 (sin comprobar) + bloque principal con los vint dados. */
    private fun rar5(vararg vints: Int): ByteArray = rar5Sig + ByteArray(4) + vints.map { it.toByte() }.toByteArray()

    @Test fun rar5_bandera_de_solido_del_bloque_principal() {
        // tamaño, tipo 1, banderas del bloque 0, banderas del archivo
        assertEquals(true, rar5ArchiveSolid(rar5(0x05, 0x01, 0x00, 0x04), 16))
        assertEquals(false, rar5ArchiveSolid(rar5(0x05, 0x01, 0x00, 0x00), 16))
        // Tamaño en vint de dos bytes y área extra presente (bandera 0x0001): se salta su tamaño.
        assertEquals(true, rar5ArchiveSolid(rar5(0x80, 0x01, 0x01, 0x01, 0x02, 0x05), 18))
        // Volumen + sólido.
        assertEquals(true, rar5ArchiveSolid(rar5(0x05, 0x01, 0x00, 0x05), 16))
        // Tipo 4 = cabecera de cifrado del archivo: no se puede saber.
        assertNull(rar5ArchiveSolid(rar5(0x05, 0x04, 0x00, 0x00), 16))
        // Cortado.
        assertNull(rar5ArchiveSolid(rar5(0x05, 0x01), 14))
    }

    @Test fun describe_cabeceras() {
        assertEquals("RAR5 sólido", ArchiveHeaders.describe(rar5(0x05, 0x01, 0x00, 0x04)))
        assertEquals("RAR5", ArchiveHeaders.describe(rar5(0x05, 0x01, 0x00, 0x00)))
        assertTrue(ArchiveHeaders.describe(rar5(0x05, 0x04, 0x00, 0x00)).startsWith("RAR5 (cabecera"))
        assertEquals("ZIP", ArchiveHeaders.describe(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)))
        assertEquals("PDF", ArchiveHeaders.describe("%PDF-1.7".toByteArray()))
        assertEquals("7z", ArchiveHeaders.describe(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)))
        assertEquals("desconocido", ArchiveHeaders.describe(ByteArray(16)))
        for (solid in listOf(false, true)) {
            val f = tmp.newFile("s$solid.cbr")
            Rar4StoredWriter.write(f, listOf(Rar4StoredWriter.Entry("1.jpg", Random(1).nextBytes(100))), solid)
            assertEquals(if (solid) "RAR4 sólido" else "RAR4", ArchiveHeaders.describe(f))
        }
    }

    @Test fun via_de_extraccion() {
        val zero = ExtractionCounters()
        assertEquals(
            "lectura directa (guardada, sin junrar) · 4 KB comprimidos leídos",
            ArchiveProbeFormat.via(ArchiveKind.RAR4, false, zero, ExtractionCounters(directReads = 1, packedBytesRead = 4096)),
        )
        assertTrue(ArchiveProbeFormat.via(ArchiveKind.RAR4, false, zero, ExtractionCounters(packedBytesRead = 10)).startsWith("junrar ·"))
        assertTrue(
            ArchiveProbeFormat.via(ArchiveKind.RAR5, true, zero, ExtractionCounters(solidDecompressCalls = 3))
                .startsWith("junrar sólido: 3 entradas"),
        )
        assertTrue(ArchiveProbeFormat.via(ArchiveKind.RAR4_SOLID, true, zero, zero).startsWith("caché de sólidos (acierto"))
        assertEquals("java.util.zip", ArchiveProbeFormat.via(ArchiveKind.ZIP, null, zero, zero))
        assertEquals("PdfRenderer → PNG", ArchiveProbeFormat.via(ArchiveKind.PDF, null, zero, zero))
    }

    @Test fun metodos() {
        assertEquals("0x30 (guardado)", ArchiveProbeFormat.rarMethod(false, 0x30))
        assertEquals("0x33 (normal)", ArchiveProbeFormat.rarMethod(false, 0x33))
        assertEquals("0 (guardado)", ArchiveProbeFormat.rarMethod(true, 0))
        assertEquals("3 (comprimido)", ArchiveProbeFormat.rarMethod(true, 3))
        assertEquals("?", ArchiveProbeFormat.rarMethod(false, null))
        assertEquals("8 (deflate)", ArchiveProbeFormat.zipMethod(8))
    }

    @Test fun diagnostico_y_contadores_de_un_rar_real_de_junrar() {
        val pages = (1..6).map { Rar4StoredWriter.Entry(String.format("%02d.jpg", it), Random(it).nextBytes(2048)) }
        // No sólido: lo guardado sale sin junrar.
        val plain = tmp.newFile("plano.cbr").also { Rar4StoredWriter.write(it, pages, solid = false) }
        ComicArchives.open(plain).use { a ->
            val before = a.extractionCounters()
            a.extractPage(0, File(tmp.newFolder(), "0.jpg"))
            val after = a.extractionCounters()
            assertTrue(a.describeExtraction(before, after), a.describeExtraction(before, after).startsWith("lectura directa"))
            val d = a.engineDiagnostics()
            assertTrue(d, d.contains("formato RAR15") && d.contains("sólido no") && d.contains("0x30 (guardado)"))
            assertTrue(d, d.contains("páginas guardadas leídas sin junrar 1"))
        }
        // Sólido: por el motor, y la vuelta atrás es un acierto de la caché.
        val solid = tmp.newFile("solido.cbr").also { Rar4StoredWriter.write(it, pages, solid = true) }
        ComicArchives.open(solid).use { a ->
            val dir = tmp.newFolder()
            val c0 = a.extractionCounters()
            a.extractPage(4, File(dir, "4.jpg"))
            val c1 = a.extractionCounters()
            assertEquals("junrar sólido: 5 entradas", a.describeExtraction(c0, c1).substringBefore(" descomprimidas"))
            a.extractPage(2, File(dir, "2.jpg"))
            assertTrue(a.describeExtraction(c1, a.extractionCounters()).startsWith("caché de sólidos (acierto"))
            assertEquals(true, a.solidAccordingToEngine())
            val d = a.engineDiagnostics()
            assertTrue(d, d.contains("sólido sí (MHD_SOLID sí)") && d.contains("caché de sólidos 5"))
        }
    }
}
