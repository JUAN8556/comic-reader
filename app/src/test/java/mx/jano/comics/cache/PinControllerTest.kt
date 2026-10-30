package mx.jano.comics.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mx.jano.comics.drive.DriveHttpException
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
import java.io.IOException

class PinControllerTest {

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

    private class FakeScheduler(var accept: Boolean = true) : PinScheduler {
        val scheduled = mutableListOf<Pair<Long, Boolean>>()
        var cancels = 0
        @Volatile
        var pending = false

        override fun schedule(estimatedBytes: Long, unmeteredOnly: Boolean): Boolean {
            synchronized(scheduled) { scheduled += estimatedBytes to unmeteredOnly }
            if (accept) pending = true
            return accept
        }

        override fun cancel() {
            cancels++
            pending = false
        }

        override fun isScheduled(): Boolean = pending
    }

    private class FakeNotifier : PinNotifier {
        var paused = 0
        var needsApp = 0
        var cleared = 0
        override fun showPaused(progress: PinProgress?) {
            paused++
        }

        override fun showNeedsApp() {
            needsApp++
        }

        override fun clear() {
            cleared++
        }
    }

    private inner class Rig(
        val env: CacheEnv = CacheEnv(tmp.root, root),
        val store: MemoryStore<PinSnapshot> = MemoryStore(),
        val scheduler: FakeScheduler = FakeScheduler(),
        val notifier: FakeNotifier = FakeNotifier(),
        var visible: Boolean = true,
    ) {
        val cache = env.manager()
        private var scope: CoroutineScope? = null

        fun controller(): PinController {
            scope?.cancel()
            val s = CoroutineScope(SupervisorJob(root.coroutineContext[Job]) + Dispatchers.IO)
            scope = s
            return PinController(
                cache = cache,
                store = store,
                scheduler = scheduler,
                settings = env.settings,
                library = env.library,
                scope = s,
                io = Dispatchers.IO,
                notifier = notifier,
                isAppVisible = { visible },
                canDownloadNow = { true },
                log = { println(it) },
            )
        }
    }

    private fun test(block: suspend CoroutineScope.() -> Unit) = runBlocking { withTimeout(20_000) { block() } }

    private suspend fun eventually(what: String, check: () -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!check()) {
            if (System.currentTimeMillis() - t0 > 5_000) fail("no llegó: $what")
            delay(10)
        }
    }

    @Test
    fun `fijar apunta, encola lo que falta, devuelve lo que queda por bajar y programa el trabajo`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        val c = rig.env.book("c", zipFor("c", 4_000))
        rig.cache.ensureLocal(a)
        val pins = rig.controller()

        val remaining = pins.pin(listOf(a, b, c))

        assertEquals(7_000L, remaining)
        assertEquals(setOf("a", "b", "c"), pins.pins.value)
        assertTrue(pins.isPinned(b))
        assertEquals(Availability.PINNED, rig.cache.availability(a)) // ya estaba: fijado al momento
        assertEquals(Availability.CLOUD, rig.cache.availability(b))
        assertEquals(listOf("b", "c"), rig.store.value!!.queue)
        assertEquals(listOf(7_000L to true), rig.scheduler.scheduled) // Wi-Fi solo por defecto
        val p = pins.progress.value!!
        assertEquals(2, p.total)
        assertEquals(0, p.done)
        assertEquals(7_000L, p.bytesTotal)
        assertEquals(3, rig.store.value!!.pinned.size) // libros enteros: se pueden bajar sin índice
    }

    @Test
    fun `el trabajo baja la cola en orden y deja todo fijado`() = test {
        val rig = Rig()
        val books = listOf(rig.env.book("a"), rig.env.book("b"), rig.env.book("c"))
        val pins = rig.controller()
        pins.pin(books)

        val seen = mutableListOf<PinProgress>()
        val result = pins.runQueue { progress, _ -> synchronized(seen) { seen += progress } }

        assertEquals(PinRunResult.Completed(3), result)
        assertNull(pins.progress.value)
        books.forEach { assertEquals(Availability.PINNED, rig.cache.availability(it)) }
        assertTrue(rig.store.value!!.queue.isEmpty())
        assertEquals(listOf("a.cbz", "b.cbz", "c.cbz"), seen.mapNotNull { it.current }.distinct())
        val last = seen.last()
        assertEquals(3, last.total)
        assertTrue(last.bytesDone <= last.bytesTotal)
        assertTrue(rig.env.store.value!!.entries.all { it.pinned })
    }

    @Test
    fun `desfijar quita de la cola y, si se vacia, cancela el trabajo`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        pins.unpin(listOf(a))
        assertEquals(listOf("b"), rig.store.value!!.queue)
        assertEquals(1, pins.progress.value!!.total)
        assertEquals(0, rig.scheduler.cancels)

        pins.unpin(listOf(b))
        assertTrue(pins.pins.value.isEmpty())
        assertNull(pins.progress.value)
        assertEquals(1, rig.scheduler.cancels)
        assertEquals(1, rig.notifier.cleared)
    }

    @Test
    fun `desfijar el que se esta bajando lo deja a medias y sigue con el siguiente`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        val gate = CompletableDeferred<Unit>()
        rig.env.drive.gate = gate
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        val run = async { pins.runQueue(PinRunListener.NONE) }
        eventually("bajando a") { rig.env.drive.calls.get() == 1 }
        pins.unpin(listOf(a))
        gate.complete(Unit)
        assertEquals(PinRunResult.Completed(1), run.await())
        assertEquals(Availability.CLOUD, rig.cache.availability(a))
        assertEquals(Availability.PINNED, rig.cache.availability(b))
    }

    @Test
    fun `pausa para y no baja y reanudar vuelve a programar`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val pins = rig.controller()
        pins.pin(listOf(a))
        pins.pause()
        eventually("pausa completa") { rig.notifier.paused == 1 }
        assertTrue(pins.paused.value)
        assertEquals(1, rig.scheduler.cancels)
        assertEquals(1, rig.notifier.paused)
        assertEquals(PinRunResult.Paused, pins.runQueue(PinRunListener.NONE))
        assertEquals(0, rig.env.drive.calls.get())
        assertTrue(rig.store.value!!.paused)

        pins.resume()
        eventually("reprogramado") { rig.scheduler.scheduled.size == 2 }
        assertFalse(pins.paused.value)
        assertEquals(PinRunResult.Completed(1), pins.runQueue(PinRunListener.NONE))
    }

    @Test
    fun `cancelar desfija lo pendiente y conserva lo ya bajado`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        rig.cache.ensureLocal(a)
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        pins.cancel()
        eventually("cancelado") { rig.notifier.cleared == 1 }
        assertEquals(setOf("a"), pins.pins.value)
        assertNull(pins.progress.value)
        assertEquals(Availability.PINNED, rig.cache.availability(a))
        assertFalse(rig.env.store.value!!.pinned.contains("b"))
        assertTrue(rig.scheduler.cancels >= 1)
    }

    @Test
    fun `al arrancar lo fijado que falta en disco vuelve a la cola y se programa`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        val first = rig.controller()
        first.pin(listOf(a, b))
        first.runQueue(PinRunListener.NONE)
        rig.scheduler.pending = false
        rig.cache.evict(b) // p. ej. se borró a mano

        val again = rig.controller() // la app vuelve a arrancar
        again.awaitReady()
        assertEquals(listOf("b"), rig.store.value!!.queue)
        eventually("reprogramado al arrancar") { rig.scheduler.scheduled.size == 2 }
        assertEquals(3_000L, rig.scheduler.scheduled.last().first)
        assertEquals(setOf("a", "b"), again.pins.value)
    }

    @Test
    fun `sin sitio para un fijado se para y lo dice`() = test {
        val env = CacheEnv(tmp.root, root, limitGb = 100, minFreeGb = 15, capacity = 20_000)
        val rig = Rig(env = env)
        val big = env.book("big", zipFor("big", 6_000))
        val pins = rig.controller()
        pins.pin(listOf(big))
        val result = pins.runQueue(PinRunListener.NONE)
        assertTrue("fue $result", result is PinRunResult.NoSpace)
        assertNotNull(pins.lastError.value)
        assertEquals(listOf("big"), rig.store.value!!.queue)
    }

    @Test
    fun `si ya no esta en Drive deja de estar fijado y sigue con el resto`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        rig.env.drive.failWith["a"] = DriveHttpException(404, "notFound", "no está")
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        assertEquals(PinRunResult.Completed(1), pins.runQueue(PinRunListener.NONE))
        assertEquals(setOf("b"), pins.pins.value)
        assertTrue(pins.lastError.value!!.contains("a.cbz"))
    }

    @Test
    fun `un fallo de red pide reintento y deja la cola intacta`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        rig.env.drive.cutAfter["a"] = 1_024
        val pins = rig.controller()
        pins.pin(listOf(a))
        val result = pins.runQueue(PinRunListener.NONE)
        assertTrue("fue $result", result is PinRunResult.Retry)
        assertEquals(listOf("a"), rig.store.value!!.queue)
        assertEquals(1_024L, rig.cache.partialBytes("a"))
        assertEquals(PinRunResult.Completed(1), pins.runQueue(PinRunListener.NONE))
    }

    @Test
    fun `sesion caducada o permiso pide abrir la app`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        rig.env.drive.failWith["a"] = DriveHttpException(403, "forbidden", "no")
        val pins = rig.controller()
        pins.pin(listOf(a))
        assertTrue(pins.runQueue(PinRunListener.NONE) is PinRunResult.NeedsApp)
    }

    @Test
    fun `si el sistema no deja programar y la app esta a la vista, baja dentro del proceso`() = test {
        val rig = Rig(scheduler = FakeScheduler(accept = false))
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        eventually("bajado en el proceso") { rig.cache.availability(b) == Availability.PINNED }
        assertEquals(Availability.PINNED, rig.cache.availability(a))
        assertEquals(0, rig.notifier.needsApp)
    }

    @Test
    fun `si el sistema no deja programar y la app no se ve, avisa de abrirla`() = test {
        val rig = Rig(scheduler = FakeScheduler(accept = false), visible = false)
        val a = rig.env.book("a")
        val pins = rig.controller()
        pins.pin(listOf(a))
        assertEquals(1, rig.notifier.needsApp)
        delay(100)
        assertEquals(0, rig.env.drive.calls.get())
    }

    @Test
    fun `si otro cancela la descarga se aparta sin desfijar`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        val b = rig.env.book("b")
        rig.env.drive.gate = CompletableDeferred()
        val pins = rig.controller()
        pins.pin(listOf(a, b))
        val run = async { pins.runQueue(PinRunListener.NONE) }
        eventually("bajando a") { rig.env.drive.calls.get() == 1 }
        rig.cache.cancelDownload("a")
        rig.env.drive.gate!!.complete(Unit)
        assertEquals(PinRunResult.Completed(1), run.await())
        assertTrue(pins.isPinned(a))
        assertEquals(Availability.CLOUD, rig.cache.availability(a))
        assertEquals(Availability.PINNED, rig.cache.availability(b))
    }

    @Test
    fun `parado desde el sistema queda en pausa`() = test {
        val rig = Rig()
        val pins = rig.controller()
        pins.pin(listOf(rig.env.book("a")))
        pins.markStoppedByUser()
        eventually("en pausa") { pins.isPausedNow }
        assertTrue(rig.store.value!!.paused)
    }

    @Test
    fun `con datos permitidos en Ajustes programa con cualquier red`() = test {
        val rig = Rig()
        rig.env.settings.app.value = AppSettings(wifiOnlyPrefetch = false, cacheLimitGb = 20, minFreeGb = 15)
        val pins = rig.controller()
        pins.pin(listOf(rig.env.book("a")))
        assertEquals(false, rig.scheduler.scheduled.single().second)
    }

    @Test
    fun `pins json sobrevive y se lee igual`() = test {
        val file = tmp.newFile("pins.json")
        val store = AtomicJsonFile(file, PinSnapshot.serializer())
        val rig = Rig(store = MemoryStore())
        val a = rig.env.book("a")
        val snapshot = PinSnapshot(pinned = listOf(a), queue = listOf("a"), paused = true, batch = PinBatch(2, 6_000), lastError = "x")
        store.save(snapshot)
        assertEquals(snapshot, AtomicJsonFile(file, PinSnapshot.serializer()).load())
    }

    @Test
    fun `un fallo de red al bajar no pierde el parcial`() = test {
        val rig = Rig()
        val a = rig.env.book("a")
        rig.env.drive.failWith["a"] = IOException("sin red")
        val pins = rig.controller()
        pins.pin(listOf(a))
        assertTrue(pins.runQueue(PinRunListener.NONE) is PinRunResult.Retry)
        rig.env.drive.failWith.remove("a")
        assertEquals(PinRunResult.Completed(1), pins.runQueue(PinRunListener.NONE))
    }
}
