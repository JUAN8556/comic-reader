package mx.jano.comics.reader

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mx.jano.comics.archive.ArchiveKind
import mx.jano.comics.archive.ComicArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** El cargador de páginas con un archivo falso (sin Android: los tamaños los da una función de prueba). */
class PageLoaderTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Archivo falso: cada página es un texto; puede bloquear la primera extracción y fallar páginas concretas. */
    private class FakeArchive(
        override val pageCount: Int,
        private val failing: MutableSet<Int> = mutableSetOf(),
        private val gate: CountDownLatch? = null,
    ) : ComicArchive {
        override val kind = ArchiveKind.ZIP
        val order: MutableList<Int> = Collections.synchronizedList(mutableListOf())
        @Volatile var started = CountDownLatch(1)

        override fun pageName(index: Int) = "p%03d.jpg".format(index + 1)

        override fun extractPage(index: Int, dest: File) {
            order += index
            started.countDown()
            gate?.await(5, TimeUnit.SECONDS)
            if (index in failing) throw IOException("página $index rota")
            dest.writeText("pagina $index")
        }

        override fun close() {}
    }

    /** Tamaños de prueba: la 3 es apaisada. */
    private val bounds: (File) -> PageSize? = { f ->
        val index = f.name.substringBefore('.').toInt()
        if (index == 3) PageSize(2000, 1500) else PageSize(1000, 1500)
    }

    @Test
    fun extrae_una_vez_y_publica_el_tamano() = runBlocking {
        val archive = FakeArchive(6)
        val loader = PageLoader(archive, tmp.newFolder("paginas"), bounds)
        try {
            val f = withTimeout(5_000) { loader.page(3) }
            assertEquals("pagina 3", f.readText())
            assertEquals("3.jpg", f.name)
            val again = withTimeout(5_000) { loader.page(3) }
            assertEquals(f, again)
            assertEquals(listOf(3), archive.order.toList())
            val sizes = withTimeout(5_000) { loader.sizes.first { 3 in it } }
            assertTrue(sizes.getValue(3).isLandscape)
            assertEquals(f, loader.pageIfReady(3))
        } finally {
            loader.close()
        }
    }

    @Test
    fun lo_que_ya_estaba_en_disco_no_se_vuelve_a_extraer() = runBlocking {
        val dir = tmp.newFolder("paginas")
        File(dir, "2.jpg").writeText("de otra sesión")
        val archive = FakeArchive(4)
        val loader = PageLoader(archive, dir, bounds)
        try {
            val f = withTimeout(5_000) { loader.page(2) }
            assertEquals("de otra sesión", f.readText())
            withTimeout(5_000) { loader.sizes.first { 2 in it } }
            assertFalse(2 in archive.order)
        } finally {
            loader.close()
        }
    }

    @Test
    fun la_pagina_pedida_en_pantalla_adelanta_a_la_ventana() = runBlocking {
        val gate = CountDownLatch(1)
        val archive = FakeArchive(10, gate = gate)
        val loader = PageLoader(archive, tmp.newFolder("paginas"), bounds)
        try {
            loader.setWindow(0, listOf(0, 1, 2, 3))
            assertTrue(archive.started.await(5, TimeUnit.SECONDS)) // el trabajador está con la 0, bloqueado
            val urgent = async { withTimeout(5_000) { loader.page(8) } }
            delay(50) // que la petición llegue antes de soltar la 0
            gate.countDown()
            urgent.await()
            withTimeout(5_000) { while (archive.order.size < 5) delay(10) }
            assertEquals(listOf(0, 8, 1, 2, 3), archive.order.toList())
        } finally {
            loader.close()
        }
    }

    @Test
    fun una_pagina_rota_falla_no_se_reintenta_sola_y_reintentar_la_vuelve_a_pedir() = runBlocking {
        val failing = mutableSetOf(2)
        val archive = FakeArchive(4, failing = failing)
        val loader = PageLoader(archive, tmp.newFolder("paginas"), bounds)
        try {
            try {
                withTimeout(5_000) { loader.page(2) }
                fail("debía fallar")
            } catch (e: IOException) {
                assertEquals("página 2 rota", e.message)
            }
            loader.setWindow(2, listOf(1, 2, 3))
            withTimeout(5_000) { while (archive.order.size < 3) delay(10) }
            delay(100)
            assertEquals("la ventana no reintenta la rota", 1, archive.order.count { it == 2 })
            failing.clear()
            loader.forget(2)
            val f = withTimeout(5_000) { loader.page(2) }
            assertEquals("pagina 2", f.readText())
        } finally {
            loader.close()
        }
    }

    @Test
    fun el_barrido_extrae_todo_el_libro_en_los_ratos_libres() = runBlocking {
        val archive = FakeArchive(7)
        val loader = PageLoader(archive, tmp.newFolder("paginas"), bounds)
        try {
            loader.sweep = true
            val sizes = withTimeout(5_000) { loader.sizes.first { it.size == 7 } }
            assertEquals((0 until 7).toSet(), sizes.keys)
            assertEquals((0 until 7).toList(), archive.order.toList().sorted())
            assertEquals(7, archive.order.size)
        } finally {
            loader.close()
        }
    }
}
