package mx.jano.comics.reader

import mx.jano.comics.settings.PageLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadEngineTest {
    private val portrait = PageSize(1000, 1500)
    private val landscape = PageSize(2000, 1500)

    private fun SpreadMap.render(): String = spreads.joinToString(" ") { s -> s.pages.joinToString("-") }

    private fun double(count: Int, landscapes: Set<Int> = emptySet(), unknown: Set<Int> = emptySet()): SpreadMap =
        SpreadEngine.build(count, EffectiveLayout.DOUBLE) { i ->
            when (i) {
                in unknown -> null
                in landscapes -> landscape
                else -> portrait
            }
        }

    @Test
    fun portada_sola_y_luego_pares() {
        assertEquals("0 1-2 3-4 5-6", double(7).render())
    }

    @Test
    fun numero_par_de_paginas_deja_la_ultima_sola() {
        assertEquals("0 1-2 3-4 5-6 7", double(8).render())
    }

    @Test
    fun una_apaisada_va_sola_y_se_reempareja_detras() {
        // La 3 es una doble página escaneada como una imagen: la 2 se queda sin pareja y la 4 empieza pares nuevos.
        assertEquals("0 1-2 3 4-5 6-7", double(8, landscapes = setOf(3)).render())
        assertEquals("0 1 2 3-4 5-6", double(7, landscapes = setOf(2)).render())
    }

    @Test
    fun apaisadas_seguidas_y_al_final() {
        assertEquals("0 1 2 3-4", double(5, landscapes = setOf(1, 2)).render())
        assertEquals("0 1-2 3", double(4, landscapes = setOf(3)).render())
        assertEquals("0 1-2 3-4 5", double(6, landscapes = setOf(5)).render())
    }

    @Test
    fun portada_apaisada_sigue_sola_y_no_cambia_nada() {
        assertEquals("0 1-2 3", double(4, landscapes = setOf(0)).render())
    }

    @Test
    fun las_desconocidas_cuentan_como_verticales_y_al_aprenderlas_se_rehace() {
        val before = double(8, unknown = setOf(3, 4, 5, 6, 7))
        assertEquals("0 1-2 3-4 5-6 7", before.render())
        val after = double(8, landscapes = setOf(4))
        assertEquals("0 1-2 3 4 5-6 7", after.render())
        assertFalse(before.samePairingAs(after))
        assertTrue(before.samePairingAs(double(8, unknown = setOf(7))))
    }

    @Test
    fun casos_minimos() {
        assertEquals("", double(0).render())
        assertTrue(double(0).isEmpty)
        assertEquals("0", double(1).render())
        assertEquals("0 1", double(2).render())
        assertEquals("0 1-2", double(3).render())
    }

    @Test
    fun una_pagina_y_vertical_son_un_pliego_por_pagina() {
        val single = SpreadEngine.build(5, EffectiveLayout.SINGLE, emptyMap())
        assertEquals("0 1 2 3 4", single.render())
        val vertical = SpreadEngine.build(3, EffectiveLayout.VERTICAL, mapOf(1 to landscape))
        assertEquals("0 1 2", vertical.render())
    }

    @Test
    fun doble_automatica_solo_en_horizontal() {
        assertEquals(EffectiveLayout.DOUBLE, SpreadEngine.effectiveLayout(PageLayout.DOUBLE_AUTO, landscape = true))
        assertEquals(EffectiveLayout.SINGLE, SpreadEngine.effectiveLayout(PageLayout.DOUBLE_AUTO, landscape = false))
        assertEquals(EffectiveLayout.SINGLE, SpreadEngine.effectiveLayout(PageLayout.SINGLE, landscape = true))
        assertEquals(EffectiveLayout.VERTICAL, SpreadEngine.effectiveLayout(PageLayout.VERTICAL, landscape = true))
        assertEquals(EffectiveLayout.VERTICAL, SpreadEngine.effectiveLayout(PageLayout.VERTICAL, landscape = false))
    }

    @Test
    fun en_manga_la_pagina_menor_va_a_la_derecha() {
        val s = Spread(3, 4)
        assertEquals(listOf(3, 4), s.leftToRight(rtl = false))
        assertEquals(listOf(4, 3), s.leftToRight(rtl = true))
        assertEquals(listOf(0), Spread(0).leftToRight(rtl = true))
    }

    @Test
    fun mapeo_pagina_pliego_en_los_dos_sentidos() {
        val map = double(8, landscapes = setOf(3)) // 0 | 1-2 | 3 | 4-5 | 6-7
        val expectedSpread = intArrayOf(0, 1, 1, 2, 3, 3, 4, 4)
        for (page in 0 until 8) {
            val spread = map.spreadOf(page)
            assertEquals("pliego de la pág. $page", expectedSpread[page], spread)
            assertTrue(page in map[spread])
            assertTrue(map.firstPageOf(spread) <= page && page <= map.lastPageOf(spread))
        }
        for (spread in 0 until map.size) {
            assertEquals(spread, map.spreadOf(map.firstPageOf(spread)))
            assertEquals(spread, map.spreadOf(map.lastPageOf(spread)))
        }
        assertEquals(5, map.size)
        assertEquals(4, map.firstPageOf(3))
        assertEquals(5, map.lastPageOf(3))
        // Fuera de rango se acota.
        assertEquals(0, map.spreadOf(-3))
        assertEquals(4, map.spreadOf(99))
        assertTrue(map.isLastSpread(4))
        assertFalse(map.isLastSpread(3))
    }

    @Test
    fun paginas_de_un_rango_de_pliegos() {
        val map = double(8) // 0 | 1-2 | 3-4 | 5-6 | 7
        assertEquals(listOf(0, 1, 2, 3, 4), map.pagesOfSpreads(-1, 2))
        assertEquals(listOf(5, 6, 7), map.pagesOfSpreads(3, 9))
        assertEquals(emptyList<Int>(), map.pagesOfSpreads(3, 2))
    }

    @Test
    fun cada_pagina_esta_en_exactamente_un_pliego() {
        for (count in 0..12) {
            for (mask in 0 until (1 shl count.coerceAtMost(10))) {
                val landscapes = (0 until count).filter { mask and (1 shl it) != 0 }.toSet()
                val map = double(count, landscapes = landscapes)
                val pages = map.spreads.flatMap { it.pages }
                assertEquals("count=$count apaisadas=$landscapes", (0 until count).toList(), pages)
                map.spreads.forEach { s ->
                    if (s.isDouble) {
                        assertFalse(s.first == 0)
                        assertFalse(s.first in landscapes || s.last in landscapes)
                    }
                }
            }
        }
    }

    @Test
    fun tamano_de_pagina() {
        assertTrue(landscape.isLandscape)
        assertFalse(portrait.isLandscape)
        assertFalse(PageSize(1000, 1000).isLandscape)
        assertEquals(1000f / 1500f, portrait.aspect, 1e-6f)
        assertEquals(0f, PageSize(10, 0).aspect, 0f)
    }
}
