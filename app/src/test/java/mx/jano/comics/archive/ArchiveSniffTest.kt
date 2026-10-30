package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class ArchiveSniffTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun padded(head: ByteArray, size: Int = 64) = head.copyOf(maxOf(size, head.size))

    @Test fun zip_las_tres_firmas() {
        assertEquals(ArchiveKind.ZIP, sniffHeader(padded(bytes(0x50, 0x4B, 0x03, 0x04))))
        assertEquals(ArchiveKind.ZIP, sniffHeader(padded(bytes(0x50, 0x4B, 0x05, 0x06)))) // ZIP vacío
        assertEquals(ArchiveKind.ZIP, sniffHeader(padded(bytes(0x50, 0x4B, 0x07, 0x08)))) // spanned
    }

    @Test fun rar5() {
        assertEquals(ArchiveKind.RAR5, sniffHeader(padded(bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))))
    }

    @Test fun rar4_sin_y_con_bandera_solida() {
        // Firma + MAIN_HEAD: CRC(2) TYPE=0x73 FLAGS(2 LE) SIZE=13 …
        val sig = bytes(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00)
        fun main(flagsLo: Int, flagsHi: Int, type: Int = 0x73) = padded(sig + bytes(0xCF, 0x90, type, flagsLo, flagsHi, 0x0D, 0x00))
        assertEquals(ArchiveKind.RAR4, sniffHeader(main(0x00, 0x00)))
        assertEquals(ArchiveKind.RAR4_SOLID, sniffHeader(main(0x08, 0x00)))
        // Otras banderas sin la de sólido (0x0040 registro de recuperación, 0x0100 primer volumen): no sólido.
        assertEquals(ArchiveKind.RAR4, sniffHeader(main(0x40, 0x01)))
        // 0x0800 (byte alto 0x08) NO es la bandera de sólido: el campo es little-endian.
        assertEquals(ArchiveKind.RAR4, sniffHeader(main(0x00, 0x08)))
        assertEquals(ArchiveKind.RAR4_SOLID, sniffHeader(main(0x88, 0x01)))
        // Si el bloque tras la firma no es un MAIN_HEAD, no se puede afirmar que sea sólido.
        assertEquals(ArchiveKind.RAR4, sniffHeader(main(0x08, 0x00, type = 0x74)))
        // Firma RAR4 truncada antes de las banderas: RAR4 a secas.
        assertEquals(ArchiveKind.RAR4, sniffHeader(sig + bytes(0xCF, 0x90, 0x73), 10))
    }

    @Test fun rar4_escrito_de_verdad_con_y_sin_solido() {
        val plain = tmp.newFile("plain.cbr")
        val solid = tmp.newFile("solid.cbr")
        val entries = listOf(Rar4TestWriter.Entry("1.jpg", byteArrayOf(1, 2, 3)))
        Rar4TestWriter.write(plain, entries, solid = false)
        Rar4TestWriter.write(solid, entries, solid = true)
        assertEquals(ArchiveKind.RAR4, ArchiveSniffer.sniff(plain))
        assertEquals(ArchiveKind.RAR4_SOLID, ArchiveSniffer.sniff(solid))
    }

    @Test fun siete_zip() {
        assertEquals(ArchiveKind.SEVEN_ZIP, sniffHeader(padded(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, 0x00, 0x04))))
    }

    @Test fun pdf_al_principio_y_con_basura_delante() {
        assertEquals(ArchiveKind.PDF, sniffHeader(padded("%PDF-1.7\n".toByteArray())))
        val junk = ByteArrayOutputStream().apply { write(ByteArray(700) { 0x20 }); write("%PDF-1.4".toByteArray()) }.toByteArray()
        assertEquals(ArchiveKind.PDF, sniffHeader(junk))
        // Más allá de los primeros 1024 bytes ya no cuenta.
        val far = ByteArray(1100) { 0x20 }.also { "%PDF".toByteArray().copyInto(it, 1050) }
        assertEquals(ArchiveKind.UNKNOWN, sniffHeader(far))
    }

    @Test fun desconocido_y_vacio() {
        assertEquals(ArchiveKind.UNKNOWN, sniffHeader(ByteArray(0)))
        assertEquals(ArchiveKind.UNKNOWN, sniffHeader("hola, esto es texto".toByteArray()))
        assertEquals(ArchiveKind.UNKNOWN, sniffHeader(bytes(0x52, 0x61, 0x72, 0x21))) // «Rar!» a medias
        assertEquals(ArchiveKind.UNKNOWN, sniffHeader(bytes(0x50, 0x4B))) // «PK» a medias
        assertEquals(ArchiveKind.UNKNOWN, ArchiveSniffer.sniff(tmp.newFile("vacio.cbr")))
        val txt = tmp.newFile("nota.cbz").apply { writeText("no soy un zip") }
        assertEquals(ArchiveKind.UNKNOWN, ArchiveSniffer.sniff(txt))
    }

    @Test fun la_extension_no_cuenta() {
        // Un ZIP llamado .cbr y un 7z llamado .cbr: manda la cabecera.
        val zipAsCbr = tmp.newFile("disfrazado.cbr").apply { writeBytes(padded(bytes(0x50, 0x4B, 0x03, 0x04))) }
        val sevenAsCbr = tmp.newFile("siete.cbr").apply { writeBytes(padded(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))) }
        assertEquals(ArchiveKind.ZIP, ArchiveSniffer.sniff(zipAsCbr))
        assertEquals(ArchiveKind.SEVEN_ZIP, ArchiveSniffer.sniff(sevenAsCbr))
    }
}
