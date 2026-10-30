package mx.jano.comics.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.settings.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** En estas pruebas «1 GB» de Ajustes son 1 000 bytes (bytesPerGb) y el suelo para abrir, 100 bytes. */
class DiskCacheManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: CoroutineScope

    @Before
    fun setUp() {
        root = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        root.cancel()
    }

    private fun env(limitGb: Int = 20, minFreeGb: Int = 15, capacity: Long = 1_000_000L, adopt: Boolean = true) =
        CacheEnv(tmp.root, root, limitGb, minFreeGb, capacity, adopt)

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - t0 > 5_000) fail("no llegó: $what")
            delay(10)
        }
    }

    // --- Descarga ---

    @Test
    fun `baja, verifica, instala con la extension real y lo apunta en cache json`() = test {
        val env = env()
        val book = env.book("b1", zipLike(5_000), name = "Uncanny X-Men 174.cbr") // es un ZIP disfrazado de .cbr
        val m = env.manager()
        assertEquals(Availability.CLOUD, m.availability(book))

        val seen = mutableListOf<DownloadProgress>()
        val file = m.ensureLocal(book) { seen += it }

        assertEquals("b1.cbz", file.name)
        assertEquals(5_000L, file.length())
        assertFalse(File(env.books, "b1.part").exists())
        assertFalse(File(env.books, "b1.part.meta").exists())
        assertEquals(Availability.LOCAL, m.availability(book))
        assertEquals(file, m.localFile(book))
        val saved = env.store.value!!.entries.single()
        assertEquals(book.contentKey, saved.contentKey)
        assertEquals(file.absolutePath, saved.file)
        assertEquals(5_000L, seen.last().bytes)
        assertEquals(5_000L, seen.last().total)
        eventually("downloads vacío") { m.downloads.value.isEmpty() }
        assertEquals(5_000L, m.state.value.usedBytes)
        assertEquals(20_000L, m.state.value.limitBytes)
        assertEquals(15_000L, m.state.value.minFreeBytes)
    }

    @Test
    fun `la extension sale de los bytes, si no del nombre, si no bin`() = test {
        val env = env()
        val pdf = env.book("p1", pdfLike(2_000), name = "Libro.cbz")
        val raro = env.book("u1", ByteArray(2_000) { 9 }, name = "Raro.CBT")
        val sinNombre = env.book("u2", ByteArray(2_000) { 9 }, name = "sin extension")
        val m = env.manager()
        assertEquals("p1.pdf", m.ensureLocal(pdf).name)
        assertEquals("u1.cbt", m.ensureLocal(raro).name)
        assertEquals("u2.bin", m.ensureLocal(sinNombre).name)
    }

    @Test
    fun `dos que piden el mismo libro comparten una sola descarga`() = test {
        val env = env()
        val book = env.book("b1")
        val gate = CompletableDeferred<Unit>()
        env.drive.gate = gate
        val m = env.manager()
        val a = async { m.ensureLocal(book) }
        val b = async { m.ensureLocal(book) }
        eventually("una llamada a Drive") { env.drive.calls.get() == 1 }
        assertEquals(Availability.DOWNLOADING, m.availability(book))
        delay(50)
        gate.complete(Unit)
        assertEquals(a.await(), b.await())
        assertEquals(1, env.drive.calls.get())
    }

    @Test
    fun `si el ultimo que esperaba se va, la descarga se cancela y deja parcial con su huella`() = test {
        val env = env()
        val book = env.book("b1", zipLike(8_000))
        env.drive.gate = CompletableDeferred()
        val m = env.manager()
        val reader = launch { m.ensureLocal(book) }
        eventually("descarga en marcha") { env.drive.calls.get() == 1 }
        reader.cancelAndJoin()
        eventually("sin descarga") { !m.isDownloading("b1") }
        assertEquals(Availability.CLOUD, m.availability(book))
        assertTrue(File(env.books, "b1.part.meta").isFile) // la huella se escribe antes del primer byte
    }

    @Test
    fun `cancelDownload despierta a quien esperaba con DownloadCancelledException`() = test {
        val env = env()
        val book = env.book("b1")
        env.drive.gate = CompletableDeferred()
        val m = env.manager()
        val waiting = async { runCatching { m.ensureLocal(book) } }
        eventually("descarga en marcha") { env.drive.calls.get() == 1 }
        m.cancelDownload("b1")
        val error = waiting.await().exceptionOrNull()
        assertTrue("fue $error", error is DownloadCancelledException)
    }

    @Test
    fun `una descarga danada se repite desde cero y a la segunda se da por mala`() = test {
        val env = env()
        val book = env.book("b1")
        env.drive.corruptTimes["b1"] = 5
        val m = env.manager()
        try {
            m.ensureLocal(book)
            fail("debía fallar")
        } catch (e: CorruptDownloadException) {
            assertTrue(e.why.startsWith("md5"))
        }
        assertEquals(2, env.drive.calls.get())
        assertFalse(File(env.books, "b1.part").exists())
        assertFalse(File(env.books, "b1.part.meta").exists())
        assertEquals(Availability.CLOUD, m.availability(book))
        assertTrue(env.store.value?.entries.isNullOrEmpty())
    }

    @Test
    fun `una descarga danada una vez se arregla sola`() = test {
        val env = env()
        val book = env.book("b1")
        env.drive.corruptTimes["b1"] = 1
        val m = env.manager()
        val file = m.ensureLocal(book)
        assertEquals(2, env.drive.calls.get())
        assertEquals(FakeDrive.md5(env.drive.contents.getValue("b1")), DiskCacheManager.md5Hex(file))
    }

    @Test
    fun `tras un corte se reanuda desde lo que hay en disco`() = test {
        val env = env()
        val book = env.book("b1", zipLike(6_000))
        env.drive.cutAfter["b1"] = 2_048
        val m = env.manager()
        try {
            m.ensureLocal(book)
            fail("debía cortarse")
        } catch (_: IOException) {
        }
        assertEquals(2_048L, File(env.books, "b1.part").length())
        assertTrue(File(env.books, "b1.part.meta").isFile)
        assertEquals(2_048L, m.partialBytes("b1"))
        assertEquals(6_000L - 2_048L, m.remainingBytes(book))

        val file = m.ensureLocal(book)
        assertEquals(6_000L, file.length())
        assertEquals(listOf(0L, 2_048L), env.drive.offsets)
    }

    @Test
    fun `solo abrir marca lastOpened`() = test {
        val env = env()
        val book = env.book("b1")
        val m = env.manager()
        m.ensureLocal(book)
        val first = env.store.value!!.entries.single().lastOpened
        env.clock.advance(1_000)
        m.ensureLocalFor(book, DownloadPurpose.PREFETCH)
        assertEquals(first, env.store.value!!.entries.single().lastOpened)
        m.ensureLocal(book)
        assertEquals(first + 1_000, env.store.value!!.entries.single().lastOpened)
    }

    // --- Versiones ---

    @Test
    fun `si el libro cambio en Drive se baja la version nueva y se tiran sus paginas`() = test {
        val env = env()
        val v1 = env.book("b1", zipLike(3_000, fill = 1))
        val m = env.manager()
        m.ensureLocal(v1)
        val pages = m.pageCacheDir(v1)
        File(pages, "0001.jpg").writeText("vieja")

        val v2 = env.book("b1", zipLike(3_500, fill = 2))
        assertEquals(Availability.CLOUD, m.availability(v2))
        assertNull(m.localFile(v2))
        val file = m.ensureLocal(v2)
        assertEquals(3_500L, file.length())
        assertEquals(v2.contentKey, env.store.value!!.entries.single().contentKey)
        assertFalse(File(pages, "0001.jpg").exists())
    }

    @Test
    fun `sin red se abre la copia vieja pero un N+1 no`() = test {
        val env = env()
        val v1 = env.book("b1", zipLike(3_000, fill = 1))
        val m = env.manager()
        val old = m.ensureLocal(v1)
        val v2 = env.book("b1", zipLike(3_100, fill = 3))
        env.drive.failWith["b1"] = IOException("sin red")
        assertEquals(old, m.ensureLocal(v2))
        try {
            m.ensureLocalFor(v2, DownloadPurpose.PREFETCH)
            fail("el N+1 no debe conformarse con la vieja")
        } catch (_: IOException) {
        }
    }

    // --- Arranque ---

    @Test
    fun `al arrancar concilia huerfanos, adopta completos y olvida lo que no esta`() = test {
        val env = env()
        env.books.mkdirs()
        env.pages.mkdirs()
        File(env.books, "huerfano.part").writeBytes(ByteArray(100))
        File(env.books, "solo.part.meta").writeText("{}")
        File(env.books, "algo.cbz.tmp").writeBytes(ByteArray(10))
        File(env.books, "sigue.part").writeBytes(ByteArray(100))
        File(env.books, "sigue.part.meta").writeText("{}")
        File(env.books, "conocido.cbz").writeBytes(zipLike(1_000))
        File(env.books, "desconocido.cbr").writeBytes(zipLike(1_200))
        File(env.books, "viejo.cbz").writeBytes(zipLike(900))
        File(env.pages, "zombi").mkdirs()
        File(env.pages, "conocido").mkdirs()
        env.drive.put("conocido", zipLike(1_000))
        val conocido = bookOf(env.drive, "conocido")
        env.library.books["conocido"] = conocido
        env.drive.put("viejo", zipLike(950))
        env.library.books["viejo"] = bookOf(env.drive, "viejo")
        env.store.value = CacheSnapshot(
            entries = listOf(CachedBook("ido", "x:1", File(env.books, "ido.cbz").absolutePath, 1, 1, false)),
        )

        val m = env.manager()
        m.awaitReady()

        val names = env.books.list()!!.toSet()
        assertEquals(setOf("sigue.part", "sigue.part.meta", "conocido.cbz", "desconocido.cbr"), names)
        assertEquals(setOf("conocido"), env.pages.list()!!.toSet())
        val entries = env.store.value!!.entries.associateBy { it.bookId }
        assertEquals(setOf("conocido", "desconocido"), entries.keys)
        assertEquals(conocido.contentKey, entries.getValue("conocido").contentKey)
        assertEquals(Availability.LOCAL, m.availability(conocido))

        // El adoptado sin índice vale si mide lo mismo; al abrirlo aprende su clave y no se baja.
        env.drive.put("desconocido", zipLike(1_200))
        val desconocido = bookOf(env.drive, "desconocido", name = "desconocido.cbr")
        assertEquals(Availability.LOCAL, m.availability(desconocido))
        m.ensureLocal(desconocido)
        assertEquals(0, env.drive.calls.get())
        assertEquals(desconocido.contentKey, env.store.value!!.entries.first { it.bookId == "desconocido" }.contentKey)
    }

    @Test
    fun `sin adoptar, un completo sin apunte se borra`() = test {
        val env = env(adopt = false)
        env.books.mkdirs()
        File(env.books, "suelto.cbz").writeBytes(zipLike(500))
        val m = env.manager()
        m.awaitReady()
        assertTrue(env.books.list()!!.isEmpty())
    }

    @Test
    fun `cache json sobrevive al reinicio`() = test {
        val env = env()
        val book = env.book("b1")
        env.manager().ensureLocal(book)
        val again = env.manager()
        again.awaitReady()
        assertEquals(Availability.LOCAL, again.availability(book))
        again.ensureLocal(book)
        assertEquals(1, env.drive.calls.get())
    }

    // --- Límites ---

    @Test
    fun `al pasar del tope desaloja terminados, luego no empezados, y lo que se esta leyendo al final`() = test {
        val env = env(limitGb = 10) // 10 000 bytes; libros de 3 000
        val a = env.book("a")
        val b = env.book("b")
        val c = env.book("c")
        val d = env.book("d")
        val e = env.book("e")
        val f = env.book("f")
        val m = env.manager()
        for (book in listOf(a, b, c)) {
            m.ensureLocal(book)
            env.clock.advance(1_000)
        }
        env.progress.set(a, ReadState.READING) // el más viejo, pero se está leyendo
        env.progress.set(b, ReadState.FINISHED)
        env.progress.set(c, ReadState.UNREAD)

        m.ensureLocal(d) // 12 000 > 10 000: sale un terminado
        env.clock.advance(1_000)
        assertEquals(Availability.CLOUD, m.availability(b))
        assertEquals(Availability.LOCAL, m.availability(a))

        m.ensureLocal(e) // sale el no empezado más viejo: c
        env.clock.advance(1_000)
        assertEquals(Availability.CLOUD, m.availability(c))

        m.ensureLocal(f) // quedan a (leyendo), d y e (no empezados): sale d
        assertEquals(Availability.CLOUD, m.availability(d))
        assertEquals(Availability.LOCAL, m.availability(a))
        assertEquals(setOf("a", "e", "f"), env.store.value!!.entries.map { it.bookId }.toSet())
        assertEquals(setOf("a.cbz", "e.cbz", "f.cbz"), env.books.list()!!.toSet())
    }

    @Test
    fun `sin progreso pasado lo descubre al desalojar`() = test {
        val env = env(limitGb = 7) // caben dos de 3 000
        val leyendo = env.book("leyendo")
        val terminado = env.book("terminado")
        val nuevo = env.book("nuevo")
        val m = env.manager(viaLookup = true)
        m.ensureLocal(leyendo)
        env.clock.advance(1_000)
        m.ensureLocal(terminado)
        env.progress.set(leyendo, ReadState.READING)
        env.progress.set(terminado, ReadState.FINISHED)
        assertNull(m.progress)
        m.ensureLocal(nuevo)
        assertEquals(Availability.CLOUD, m.availability(terminado)) // LRU puro habría quitado «leyendo»
        assertEquals(Availability.LOCAL, m.availability(leyendo))
        assertNotNull(m.progress)
    }

    @Test
    fun `nunca desaloja fijados ni protegidos`() = test {
        val env = env(limitGb = 20)
        val a = env.book("a")
        val b = env.book("b")
        val c = env.book("c")
        val m = env.manager()
        m.ensureLocal(a)
        m.ensureLocal(b)
        m.ensureLocal(c)
        m.setPinnedIds(setOf("a"))
        assertEquals(Availability.PINNED, m.availability(a))

        m.enforceLimits(protect = setOf("b", "c")) // el abierto y su N+1
        env.settings.app.value = env.settings.app.value.copy(cacheLimitGb = 3) // dispara un desalojo al momento
        delay(300)
        m.enforceLimits(protect = setOf("b", "c"))
        assertEquals(setOf("a", "b", "c"), env.store.value!!.entries.map { it.bookId }.toSet())
        assertTrue(m.state.value.usedBytes > m.state.value.limitBytes)

        m.enforceLimits(protect = setOf("c"))
        assertEquals(setOf("a", "c"), env.store.value!!.entries.map { it.bookId }.toSet())
        assertTrue(env.store.value!!.entries.first { it.bookId == "a" }.pinned)
    }

    @Test
    fun `bajar el tope en Ajustes desaloja al momento`() = test {
        val env = env(limitGb = 20)
        val a = env.book("a")
        val b = env.book("b")
        val m = env.manager()
        m.ensureLocal(a)
        env.clock.advance(10)
        m.ensureLocal(b)
        env.settings.app.value = env.settings.app.value.copy(cacheLimitGb = 4)
        eventually("desalojo por Ajustes") { m.availability(a) == Availability.CLOUD }
        assertEquals(Availability.LOCAL, m.availability(b))
    }

    @Test
    fun `respeta el margen libre del volumen`() = test {
        // Volumen de 30 000 con 15 000 siempre libres; tope holgado.
        val env = env(limitGb = 100, minFreeGb = 15, capacity = 30_000)
        val books = (1..4).map { env.book("v$it", zipLike(5_000)) }
        val m = env.manager()
        books.take(3).forEach {
            m.ensureLocal(it)
            env.clock.advance(10)
        }
        assertEquals(15_000L, m.state.value.deviceFreeBytes)
        m.ensureLocal(books[3]) // dejaría 10 000 libres: sale el más viejo
        assertEquals(Availability.CLOUD, m.availability(books[0]))
        assertEquals(15_000L, m.state.value.deviceFreeBytes)
    }

    @Test
    fun `sin sitio el N+1 y el fijado no se bajan, el que se abre si`() = test {
        val env = env(limitGb = 100, minFreeGb = 15, capacity = 20_000)
        val book = env.book("big", zipLike(6_000))
        val m = env.manager()
        for (purpose in listOf(DownloadPurpose.PREFETCH, DownloadPurpose.PIN)) {
            try {
                m.ensureLocalFor(book, purpose)
                fail("$purpose no debía caber")
            } catch (e: InsufficientSpaceException) {
                assertEquals(purpose, e.purpose)
            }
        }
        assertEquals(0, env.drive.calls.get())
        assertEquals(6_000L, m.ensureLocal(book).length())
    }

    @Test
    fun `el fijado puede pasar del tope pero no del margen`() = test {
        val env = env(limitGb = 5, minFreeGb = 1, capacity = 100_000)
        val a = env.book("a")
        val b = env.book("b")
        val m = env.manager()
        m.setPinnedIds(setOf("a", "b"))
        m.ensureLocalFor(a, DownloadPurpose.PIN)
        m.ensureLocalFor(b, DownloadPurpose.PIN) // 6 000 > 5 000, pero son fijados
        assertEquals(Availability.PINNED, m.availability(a))
        assertEquals(Availability.PINNED, m.availability(b))
        val c = env.book("c")
        try {
            m.ensureLocalFor(c, DownloadPurpose.PREFETCH)
            fail("el N+1 no cabe en el tope")
        } catch (_: InsufficientSpaceException) {
        }
    }

    // --- Borrar ---

    @Test
    fun `evict borra el libro, su parcial y sus paginas`() = test {
        val env = env()
        val book = env.book("b1")
        val m = env.manager()
        val file = m.ensureLocal(book)
        File(m.pageCacheDir(book), "1.jpg").writeText("x")
        m.evict(book)
        assertFalse(file.exists())
        assertFalse(File(env.pages, "b1").exists())
        assertEquals(Availability.CLOUD, m.availability(book))
        assertTrue(env.store.value!!.entries.isEmpty())
    }

    @Test
    fun `clearAll vacia todo salvo lo fijado`() = test {
        val env = env()
        val keep = env.book("keep")
        val drop = env.book("drop")
        val m = env.manager()
        m.ensureLocal(keep)
        m.ensureLocal(drop)
        File(env.books, "otro.part").writeBytes(ByteArray(10))
        File(env.books, "otro.part.meta").writeText("{}")
        m.setPinnedIds(setOf("keep"))
        m.clearAll()
        assertEquals(setOf("keep.cbz"), env.books.list()!!.toSet())
        assertEquals(Availability.PINNED, m.availability(keep))
        assertEquals(Availability.CLOUD, m.availability(drop))
    }

    @Test
    fun `nombres y extensiones`() {
        assertEquals("abc_DEF-123", DiskCacheManager.safeName("abc_DEF-123"))
        assertEquals("a_b_c", DiskCacheManager.safeName("a/b.c"))
        assertTrue(DiskCacheManager.isCompleteName("x.cbz"))
        assertFalse(DiskCacheManager.isCompleteName("x.part"))
        assertFalse(DiskCacheManager.isCompleteName("x.part.meta"))
        assertFalse(DiskCacheManager.isCompleteName("x.cbz.tmp"))
        assertEquals("bin", DiskCacheManager.extensionFor(mx.jano.comics.archive.ArchiveKind.UNKNOWN, "trampa.part"))
        assertEquals("cbr", DiskCacheManager.extensionFor(mx.jano.comics.archive.ArchiveKind.RAR4_SOLID, "x.cbz"))
        assertEquals("cb7", DiskCacheManager.extensionFor(mx.jano.comics.archive.ArchiveKind.SEVEN_ZIP, "x.cbr"))
    }

    @Test
    fun `pageCacheDir existe y es por libro`() = test {
        val env = env()
        val m = env.manager()
        val dir = m.pageCacheDir(Book("id1", "x.cbz", "f", "x.cbz", 10))
        assertTrue(dir.isDirectory)
        assertEquals(File(env.pages, "id1"), dir)
        assertNotNull(m.state.value)
    }
}
