package mx.jano.comics.reader

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mx.jano.comics.library.ReadingProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPolicyTest {

    private fun progress(page: Int, count: Int, finished: Boolean = false) =
        ReadingProgress(contentKey = "md5:1", page = page, pageCount = count, updatedAt = 0, finished = finished)

    @Test
    fun pagina_de_arranque() {
        assertEquals(0, ReadingPolicy.startPage(null, 36))
        assertEquals(12, ReadingPolicy.startPage(progress(12, 36), 36))
        // Terminado y dejado en su final: se vuelve a abrir por el principio.
        assertEquals(0, ReadingPolicy.startPage(progress(35, 36, finished = true), 36))
        assertEquals(0, ReadingPolicy.startPage(progress(0, 36, finished = true), 36))
        // Terminado pero releyéndose por la mitad: se retoma la relectura.
        assertEquals(50, ReadingPolicy.startPage(progress(50, 160, finished = true), 160))
        assertEquals(119, ReadingPolicy.startPage(progress(119, 400, finished = true), 400))
        // Terminado en un archivo que ahora tiene más páginas: su final guardado sigue siendo el final.
        assertEquals(0, ReadingPolicy.startPage(progress(35, 36, finished = true), 40))
        // El archivo cambió y tiene menos páginas: se acota.
        assertEquals(19, ReadingPolicy.startPage(progress(30, 36), 20))
        assertEquals(0, ReadingPolicy.startPage(progress(5, 36), 0))
    }

    @Test
    fun umbral_del_ochenta_por_ciento() {
        // 36 páginas: el 80 % son 28,8 → se pide el N+1 al ver la 30 (índice 29).
        assertFalse(ReadingPolicy.crossedPrefetchThreshold(27, 36))
        assertFalse(ReadingPolicy.crossedPrefetchThreshold(28, 36))
        assertTrue(ReadingPolicy.crossedPrefetchThreshold(29, 36))
        assertTrue(ReadingPolicy.crossedPrefetchThreshold(35, 36))
        // Libros diminutos: la última página siempre cuenta.
        assertTrue(ReadingPolicy.crossedPrefetchThreshold(0, 1))
        assertFalse(ReadingPolicy.crossedPrefetchThreshold(0, 3))
        assertTrue(ReadingPolicy.crossedPrefetchThreshold(2, 3))
        assertFalse(ReadingPolicy.crossedPrefetchThreshold(0, 0))
    }

    @Test
    fun posicion_que_ve_la_ultima_pagina() {
        assertTrue(ReaderPosition(page = 34, lastPage = 35, pageCount = 36).reachedLastPage)
        assertFalse(ReaderPosition(page = 33, lastPage = 34, pageCount = 36).reachedLastPage)
        assertTrue(ReaderPosition(page = 34, lastPage = 34, pageCount = 36, atEnd = true).reachedLastPage)
        assertFalse(ReaderPosition(page = 0, lastPage = 0, pageCount = 0).reachedLastPage)
    }

    private class Recorder {
        val saves = mutableListOf<Pair<Int, Int>>()
        val finished = mutableListOf<Int>()
        val log = mutableListOf<String>()
    }

    private fun kotlinx.coroutines.test.TestScope.saver(rec: Recorder) = ProgressSaver(
        scope = backgroundScope,
        debounceMs = 500,
        save = { p, c -> rec.saves += p to c; rec.log += "save $p" },
        markFinished = { c -> rec.finished += c; rec.log += "finished" },
    )

    @Test
    fun debounce_guarda_solo_la_ultima_pagina() = runTest {
        val rec = Recorder()
        val s = saver(rec)
        s.onPosition(ReaderPosition(1, 2, 36))
        advanceTimeBy(200)
        s.onPosition(ReaderPosition(3, 4, 36))
        advanceTimeBy(200)
        s.onPosition(ReaderPosition(5, 6, 36))
        advanceTimeBy(499)
        runCurrent()
        assertEquals(emptyList<Pair<Int, Int>>(), rec.saves)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf(5 to 36), rec.saves)
        assertEquals(emptyList<Int>(), rec.finished)
    }

    @Test
    fun la_misma_pagina_no_se_guarda_dos_veces() = runTest {
        val rec = Recorder()
        val s = saver(rec)
        s.onPosition(ReaderPosition(7, 8, 36))
        advanceTimeBy(600)
        s.onPosition(ReaderPosition(7, 8, 36))
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf(7 to 36), rec.saves)
    }

    @Test
    fun al_salir_se_guarda_ya_y_despues_no_se_acepta_nada() = runTest {
        val rec = Recorder()
        val s = saver(rec)
        s.onPosition(ReaderPosition(9, 10, 36))
        advanceTimeBy(100)
        s.flush(close = true)
        runCurrent()
        assertEquals(listOf(9 to 36), rec.saves)
        assertTrue(s.closed)
        s.onPosition(ReaderPosition(11, 12, 36))
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(listOf(9 to 36), rec.saves)
    }

    @Test
    fun la_ultima_pagina_marca_terminado_despues_de_guardar_y_volver_atras_no_pisa_la_pagina() = runTest {
        val rec = Recorder()
        val s = saver(rec)
        s.onPosition(ReaderPosition(34, 35, 36))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf("save 34", "finished"), rec.log)
        // Volver atrás en la misma sesión guarda la página de verdad, sin repetir «terminado».
        s.onPosition(ReaderPosition(30, 31, 36))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf("save 34", "finished", "save 30"), rec.log)
        // Volver al final lo marca otra vez (el almacén deja la página en la última).
        s.onPosition(ReaderPosition(34, 35, 36))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf("save 34", "finished", "save 30", "save 34", "finished"), rec.log)
        assertEquals(listOf(36, 36), rec.finished)
    }

    @Test
    fun sin_paginas_no_se_guarda_nada() = runTest {
        val rec = Recorder()
        val s = saver(rec)
        s.onPosition(ReaderPosition(0, 0, 0))
        advanceTimeBy(1000)
        s.flush()
        runCurrent()
        assertEquals(emptyList<String>(), rec.log)
    }
}
