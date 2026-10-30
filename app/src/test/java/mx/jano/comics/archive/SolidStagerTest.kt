package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.OutputStream

/**
 * La caché de sólidos contra un motor falso que se comporta como junrar 8.1.2 en un sólido:
 * pedir una entrada posterior cuesta las de en medio; pedir una anterior (o la misma) cuesta todas desde la primera.
 */
class SolidStagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakeSolidEngine(private val entries: Int) {
        var last = -1
        var work = 0 // entradas descomprimidas en total (incluidas las saltadas)
        var failAt: Int? = null
        fun decompress(entry: Int, out: OutputStream) {
            require(entry in 0 until entries)
            work += if (entry > last) entry - last else entry + 1
            if (failAt == entry) {
                last = Int.MAX_VALUE // como junrar tras un fallo
                throw IOException("fallo simulado en $entry")
            }
            last = entry
            out.write("entrada $entry".toByteArray())
        }
    }

    @Test fun adelante_y_atras_cuesta_n() {
        val n = 50
        val engine = FakeSolidEngine(n)
        val stager = SolidStager(IntArray(n) { it }, engine::decompress)
        val dir = tmp.newFolder()
        for (p in 0 until n) stager.stage(dir, p)
        for (p in n - 1 downTo 0) stager.stage(dir, p)
        assertEquals(n, engine.work)
    }

    @Test fun empezar_por_la_ultima_cuesta_n_y_lo_demas_es_gratis() {
        val n = 30
        val engine = FakeSolidEngine(n)
        val stager = SolidStager(IntArray(n) { it }, engine::decompress)
        val dir = tmp.newFolder()
        val last = stager.stage(dir, n - 1)
        assertEquals("entrada ${n - 1}", last.readText())
        assertEquals(n, engine.work)
        for (p in listOf(0, 15, 3, 29, 1)) assertEquals("entrada $p", stager.stage(dir, p).readText())
        assertEquals(n, engine.work)
    }

    @Test fun orden_natural_distinto_del_fisico() {
        // Físico: [10.jpg, nota.txt, 1.jpg, 2.jpg]; natural: [1.jpg, 2.jpg, 10.jpg] → entradas [2, 3, 0].
        val engine = FakeSolidEngine(4)
        val stager = SolidStager(intArrayOf(2, 3, 0), engine::decompress)
        val dir = tmp.newFolder()
        assertEquals("entrada 2", stager.stage(dir, 0).readText()) // deja también la entrada 0 (página 2)
        assertEquals("entrada 3", stager.stage(dir, 1).readText())
        assertEquals("entrada 0", stager.stage(dir, 2).readText()) // acierto: no rebobina
        assertEquals(4, engine.work)
    }

    @Test fun si_borran_una_pagina_de_la_cache_se_rebobina_bien() {
        val n = 10
        val engine = FakeSolidEngine(n)
        val stager = SolidStager(IntArray(n) { it }, engine::decompress)
        val dir = tmp.newFolder()
        stager.stage(dir, n - 1)
        assertTrue(stager.stagedFile(dir, 4).delete())
        assertEquals("entrada 4", stager.stage(dir, 4).readText())
        assertEquals("entrada 9", stager.stage(dir, 9).readText())
        assertEquals(n + 5, engine.work) // una sola rebobinada hasta la 4
    }

    @Test fun tras_un_fallo_se_recupera() {
        val n = 6
        val engine = FakeSolidEngine(n).apply { failAt = 3 }
        val stager = SolidStager(IntArray(n) { it }, engine::decompress)
        val dir = tmp.newFolder()
        val e = runCatching { stager.stage(dir, 5) }.exceptionOrNull()
        assertTrue("$e", e is IOException)
        assertTrue(!stager.stagedFile(dir, 3).exists()) // nada a medias
        engine.failAt = null
        assertEquals("entrada 5", stager.stage(dir, 5).readText())
        assertEquals("entrada 3", stager.stage(dir, 3).readText())
    }
}
