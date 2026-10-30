package mx.jano.comics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Planificador de extracción, guarda de bordes, formatos y resolución del modo manga. */
class ReaderPureTest {

    // ---- ExtractionPlanner ----

    private fun pick(
        focus: Int,
        urgent: Set<Int> = emptySet(),
        window: List<Int> = emptyList(),
        done: Set<Int> = emptySet(),
        failed: Set<Int> = emptySet(),
        sweep: Boolean = false,
        count: Int = 20,
    ) = ExtractionPlanner.pick(count, focus, urgent, window, { it in done }, { it in failed }, sweep)

    @Test
    fun lo_urgente_va_antes_que_la_ventana() {
        assertEquals(9, pick(focus = 5, urgent = setOf(9), window = listOf(4, 5, 6, 7)))
        // Entre dos urgentes, la más cercana; a igual distancia, la de delante.
        assertEquals(6, pick(focus = 5, urgent = setOf(4, 6)))
        assertEquals(4, pick(focus = 5, urgent = setOf(4, 7)))
    }

    @Test
    fun la_ventana_por_cercania_y_delante_antes_que_detras() {
        assertEquals(5, pick(focus = 5, window = listOf(3, 4, 5, 6, 7)))
        assertEquals(6, pick(focus = 5, window = listOf(3, 4, 5, 6, 7), done = setOf(5)))
        assertEquals(4, pick(focus = 5, window = listOf(3, 4, 5, 6, 7), done = setOf(5, 6)))
        assertEquals(7, pick(focus = 5, window = listOf(3, 4, 5, 6, 7), done = setOf(4, 5, 6)))
        assertTrue(ExtractionPlanner.cost(6, 5) < ExtractionPlanner.cost(4, 5))
        assertTrue(ExtractionPlanner.cost(4, 5) < ExtractionPlanner.cost(7, 5))
    }

    @Test
    fun las_fallidas_no_se_reintentan_salvo_si_se_piden() {
        assertEquals(6, pick(focus = 5, window = listOf(5, 6), failed = setOf(5)))
        assertEquals(5, pick(focus = 5, urgent = setOf(5), window = listOf(5, 6), failed = setOf(5)))
        assertNull(pick(focus = 5, window = listOf(5), failed = setOf(5), sweep = true, count = 6, done = setOf(0, 1, 2, 3, 4)))
    }

    @Test
    fun el_barrido_va_desde_el_principio_y_solo_si_esta_encendido() {
        assertNull(pick(focus = 10, window = listOf(10), done = setOf(10)))
        assertEquals(0, pick(focus = 10, window = listOf(10), done = setOf(10), sweep = true))
        assertEquals(2, pick(focus = 10, done = setOf(0, 1, 10), sweep = true))
        assertNull(pick(focus = 1, done = (0 until 20).toSet(), sweep = true))
        // Fuera de rango se ignora.
        assertNull(pick(focus = 0, urgent = setOf(25, -1), window = listOf(30), count = 20))
    }

    @Test
    fun ventanas() {
        val map = SpreadEngine.build(9, EffectiveLayout.DOUBLE, emptyMap()) // 0 | 1-2 | 3-4 | 5-6 | 7-8
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), ExtractionPlanner.pagedWindow(map, currentSpread = 2))
        assertEquals(listOf(0, 1, 2, 3, 4), ExtractionPlanner.pagedWindow(map, currentSpread = 0))
        assertEquals((0..6).toList(), ExtractionPlanner.verticalWindow(0, 3, 40))
        assertEquals((7..19).toList(), ExtractionPlanner.verticalWindow(10, 16, 20))
        assertEquals(emptyList<Int>(), ExtractionPlanner.verticalWindow(0, 0, 0))
    }

    // ---- EdgeGuard (UI-4) ----

    @Test
    fun franja_central_de_200dp_en_cada_borde() {
        // 1440 x 2200 px a 2 px/dp: 200 dp = 400 px, centrada; ancho de la zona de gesto del sistema.
        val rects = EdgeGuard.exclusionRects(1440, 2200, leftInset = 60, rightInset = 60, maxBandPx = 400, fallbackEdgePx = 64)
        assertEquals(listOf(PxRect(0, 900, 60, 1300), PxRect(1380, 900, 1440, 1300)), rects)
        rects.forEach { assertEquals(400, it.height) }
    }

    @Test
    fun sin_insets_se_usa_el_ancho_de_reserva_y_la_franja_no_pasa_del_alto() {
        val rects = EdgeGuard.exclusionRects(800, 300, leftInset = 0, rightInset = 0, maxBandPx = 400, fallbackEdgePx = 64)
        assertEquals(listOf(PxRect(0, 0, 64, 300), PxRect(736, 0, 800, 300)), rects)
        assertEquals(emptyList<PxRect>(), EdgeGuard.exclusionRects(0, 100, 10, 10, 400, 64))
    }

    // ---- Formatos ----

    private val mx = Locale.forLanguageTag("es-MX")
    private val es = Locale.forLanguageTag("es-ES")

    @Test
    fun megas_y_velocidad() {
        assertEquals("12.3 MB", ReaderFormat.megabytes(12_897_485, mx))
        assertEquals("12,3 MB", ReaderFormat.megabytes(12_897_485, es))
        assertEquals("12.3 de 40.0 MB", ReaderFormat.downloaded(12_897_485, 40L * 1024 * 1024, mx))
        assertEquals("5.0 MB", ReaderFormat.downloaded(5L * 1024 * 1024, null, mx))
        assertEquals("1,840 KB/s", ReaderFormat.speed(1840L * 1024, mx))
        assertEquals("0 KB/s", ReaderFormat.speed(-5, mx))
    }

    @Test
    fun fraccion_y_tiempo_que_falta() {
        assertEquals(0.25f, ReaderFormat.fraction(10, 40)!!, 1e-6f)
        assertNull(ReaderFormat.fraction(10, null))
        assertEquals(1f, ReaderFormat.fraction(50, 40)!!, 0f)
        assertEquals(15L, ReaderFormat.secondsLeft(10, 40, 2))
        assertNull(ReaderFormat.secondsLeft(10, 40, 0))
        assertEquals("quedan 25 s", ReaderFormat.timeLeft(25))
        assertEquals("quedan 2 min", ReaderFormat.timeLeft(100))
        assertEquals("37 %", ReaderFormat.percent(0.379f))
    }

    @Test
    fun etiqueta_de_paginas() {
        assertEquals("5 / 36", ReaderFormat.pagesLabel(4, 4, 36))
        assertEquals("5-6 / 36", ReaderFormat.pagesLabel(4, 5, 36))
    }

    // ---- Manga ----

    @Test
    fun manga_heredado_y_propio() {
        assertEquals("Manga/Akira/\u0000heredado", MangaResolution.inheritedProbePath("Manga/Akira/Akira 01.cbz"))
        assertEquals("\u0000heredado", MangaResolution.inheritedProbePath("suelto.cbz"))
        assertEquals(MangaChoice.INHERIT, MangaResolution.choiceFor(effective = true, inherited = true))
        assertEquals(MangaChoice.INHERIT, MangaResolution.choiceFor(effective = false, inherited = false))
        assertEquals(MangaChoice.YES, MangaResolution.choiceFor(effective = true, inherited = false))
        assertEquals(MangaChoice.NO, MangaResolution.choiceFor(effective = false, inherited = true))
        assertEquals(true, MangaResolution.override(MangaChoice.YES))
        assertEquals(false, MangaResolution.override(MangaChoice.NO))
        assertNull(MangaResolution.override(MangaChoice.INHERIT))
        assertTrue(MangaResolution.effective(MangaChoice.INHERIT, inherited = true))
        assertFalse(MangaResolution.effective(MangaChoice.NO, inherited = true))
    }
}
