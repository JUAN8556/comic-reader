package mx.jano.comics.ui.series

import mx.jano.comics.metadata.SeriesMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesLogicTest {
    @Test fun rowsSkipEmptyFieldsAndKeepTheReadingOrder() {
        val meta = SeriesMeta(
            ruta = "Manga/Berserk",
            titulo = "Berserk",
            titulo_original = "Berserk",
            tipo = "Manga",
            guion = listOf("Kentaro Miura", " "),
            editorial_original = "  ",
            generos = listOf("Fantasía", "Seinen"),
            numeros_totales = 41,
            numeros_en_carpeta = 40,
        )
        val rows = SeriesLogic.rows(meta)
        assertEquals(listOf("Tipo", "Guion", "Géneros", "Números"), rows.map { it.label })
        assertEquals("Kentaro Miura", rows[1].value)
        assertEquals("Fantasía, Seinen", rows[2].value)
        assertEquals("40 en la carpeta de 41 publicados", rows[3].value)
    }

    @Test fun originalTitleOnlyWhenItDiffers() {
        val rows = SeriesLogic.rows(SeriesMeta(ruta = "x", titulo = "El Eternauta", titulo_original = "El eternauta (1957)"))
        assertEquals("Título original", rows.first().label)
    }

    @Test fun titleFallsBackToTheFolderName() {
        assertEquals("Mafalda", SeriesLogic.title(null, "Mafalda"))
        assertEquals("Mafalda", SeriesLogic.title(SeriesMeta(ruta = "x", titulo = " "), "Mafalda"))
        assertEquals("Mafalda (Quino)", SeriesLogic.title(SeriesMeta(ruta = "x", titulo = "Mafalda (Quino)"), "Mafalda"))
    }

    @Test fun lowConfidenceAndLinks() {
        assertTrue(SeriesLogic.isLowConfidence(SeriesMeta(ruta = "x", confianza = " Baja ")))
        assertFalse(SeriesLogic.isLowConfidence(SeriesMeta(ruta = "x", confianza = "media")))
        assertFalse(SeriesLogic.isLowConfidence(SeriesMeta(ruta = "x")))
        assertTrue(SeriesLogic.isLink("https://es.wikipedia.org/wiki/Mafalda"))
        assertFalse(SeriesLogic.isLink("Tebeosfera, ficha 123"))
    }
}
