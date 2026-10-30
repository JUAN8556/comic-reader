package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComicArchivesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun siete_zip_disfrazado_da_error_claro() {
        val f = tmp.newFile("siete.cbr").apply {
            writeBytes(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4) + ByteArray(56))
        }
        val e = runCatching { ComicArchives.open(f) }.exceptionOrNull()
        assertTrue("$e", e is UnsupportedArchiveException)
        assertEquals(ArchiveKind.SEVEN_ZIP, (e as UnsupportedArchiveException).kind)
        assertTrue(e.message!!, e.message!!.contains("7z"))
    }

    @Test fun vacio_y_desconocido_dan_error_claro() {
        for (f in listOf(tmp.newFile("vacio.cbz"), tmp.newFile("texto.cbr").apply { writeText("no soy un cómic") })) {
            val e = runCatching { ComicArchives.open(f) }.exceptionOrNull()
            assertTrue("$e", e is UnsupportedArchiveException)
            assertEquals(ArchiveKind.UNKNOWN, (e as UnsupportedArchiveException).kind)
        }
    }

    @Test fun las_excepciones_son_de_entrada_salida() {
        // Quien ya captura IOException al descargar o abrir no necesita otro catch.
        val errors: List<Exception> = listOf(
            UnsupportedArchiveException(ArchiveKind.UNKNOWN, "x"),
            EncryptedArchiveException(ArchiveKind.RAR4, "x"),
            CorruptArchiveException(ArchiveKind.ZIP, "x"),
        )
        assertTrue(errors.all { it is java.io.IOException })
    }
}
