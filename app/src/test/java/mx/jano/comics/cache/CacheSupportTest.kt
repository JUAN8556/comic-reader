package mx.jano.comics.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CacheSupportTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `el json atomico guarda, relee y no deja temporales`() {
        val file = tmp.root.resolve("files/cache.json")
        val store = AtomicJsonFile(file, CacheSnapshot.serializer())
        assertNull(store.load())
        val snapshot = CacheSnapshot(
            entries = listOf(CachedBook("b1", "abc:10", "/x/b1.cbz", 10, 5, pinned = true)),
            pinned = setOf("b1"),
        )
        store.save(snapshot)
        store.save(snapshot.copy(pinned = emptySet()))
        assertEquals(snapshot.copy(pinned = emptySet()), AtomicJsonFile(file, CacheSnapshot.serializer()).load())
        assertEquals(listOf("cache.json"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun `un json roto se lee como ausente`() {
        val file = tmp.newFile("cache.json")
        file.writeText("{\"entries\": [ {\"bookId\": ")
        assertNull(AtomicJsonFile(file, CacheSnapshot.serializer()).load())
    }

    @Test
    fun `un temporal a medias de un corte no pisa al bueno`() {
        val file = tmp.root.resolve("pins.json")
        val store = AtomicJsonFile(file, PinSnapshot.serializer())
        store.save(PinSnapshot(queue = listOf("a")))
        tmp.root.resolve("pins.json.tmp").writeText("{ basura")
        assertEquals(listOf("a"), store.load()!!.queue)
    }

    @Test
    fun `la velocidad sale de la ventana deslizante`() {
        val clock = FakeClock(0)
        val meter = SpeedMeter(clock, windowMs = 3_000)
        assertEquals(0, meter.add(1_000_000)) // reanudación: lo que ya había no cuenta como velocidad
        clock.advance(1_000)
        assertEquals(500_000, meter.add(1_500_000))
        clock.advance(1_000)
        assertEquals(500_000, meter.add(2_000_000))
        // Se para la red: tras la ventana solo quedan las muestras recientes.
        clock.advance(5_000)
        meter.add(2_000_000)
        clock.advance(1_000)
        assertEquals(0, meter.add(2_000_000))
        clock.advance(1_000)
        assertEquals(1_000_000, meter.add(4_000_000)) // 2 MB en 2 s
    }

    @Test
    fun `los bytes se leen como en Ajustes de Android`() {
        assertEquals("0 B", CacheStrings.formatBytes(0))
        assertEquals("950 B", CacheStrings.formatBytes(950))
        assertEquals("95 KB", CacheStrings.formatBytes(95_400))
        assertEquals("40 MB", CacheStrings.formatBytes(40_123_456))
        assertEquals("1,3 GB", CacheStrings.formatBytes(1_260_000_000))
        assertEquals("20,0 GB", CacheStrings.formatBytes(20_000_000_000))
    }

    @Test
    fun `la red cuenta si tiene internet validada y si no se paga por datos`() {
        assertEquals(NetworkStatus(online = true, unmetered = true), NetworkStatus.of(true, true, true, false))
        assertEquals(NetworkStatus(online = true, unmetered = false), NetworkStatus.of(true, true, false, false))
        assertEquals(NetworkStatus(online = true, unmetered = true), NetworkStatus.of(true, true, false, true))
        assertEquals(NetworkStatus(online = false, unmetered = true), NetworkStatus.of(true, false, true, false)) // portal cautivo
        assertFalse(NetworkStatus.OFFLINE.allows(unmeteredOnly = false))
        assertTrue(NetworkStatus(online = true, unmetered = false).allows(unmeteredOnly = false))
        assertFalse(NetworkStatus(online = true, unmetered = false).allows(unmeteredOnly = true))
        assertTrue(NetworkStatus(online = true, unmetered = true).allows(unmeteredOnly = true))
    }

    @Test
    fun `las transferencias salen con su motivo, primero lo que corre`() {
        val downloads = mapOf(
            "pre" to DownloadProgress("pre", 100, 1_000, 50),
            "pin" to DownloadProgress("pin", 200, null, 60),
            "abre" to DownloadProgress("abre", 300, 3_000, 70),
            "suelto" to DownloadProgress("suelto", 10, 100, 1),
        )
        val purposes = mapOf("pre" to DownloadPurpose.PREFETCH, "pin" to DownloadPurpose.PIN, "abre" to DownloadPurpose.READ)
        val items = transferItems(
            downloads = downloads,
            queuedPrefetch = listOf("pre", "cola1", "cola2", "cola1"),
            purposeOf = { purposes[it] },
            sizeOf = { if (it == "pin") 2_000L else if (it == "cola1") 5_000L else null },
        )
        assertEquals(
            listOf(
                TransferItem("abre", TransferReasons.READ, 300, 3_000),
                TransferItem("suelto", TransferReasons.READ, 10, 100),
                TransferItem("pin", TransferReasons.PIN, 200, 2_000),
                TransferItem("pre", TransferReasons.PREFETCH, 100, 1_000),
                TransferItem("cola1", TransferReasons.PREFETCH, 0, 5_000),
                TransferItem("cola2", TransferReasons.PREFETCH, 0, null),
            ),
            items,
        )
        assertEquals("Siguiente número", TransferReasons.reasonLabel(TransferReasons.PREFETCH))
        assertEquals("Sin conexión", TransferReasons.reasonLabel(TransferReasons.PIN))
    }

    @Test
    fun `una descarga sin motivo conocido pero en la cola del N+1 es prefetch`() {
        val items = transferItems(
            downloads = mapOf("x" to DownloadProgress("x", 1, 2, 0)),
            queuedPrefetch = listOf("x"),
            purposeOf = { null },
            sizeOf = { null },
        )
        assertEquals(listOf(TransferItem("x", TransferReasons.PREFETCH, 1, 2)), items)
    }

    @Test
    fun `el id de libro sale de las etiquetas del trabajo`() {
        assertEquals("abc", WorkManagerTransferQueue.bookIdOf(setOf("comics-prefetch", "comics-book:abc")))
        assertNull(WorkManagerTransferQueue.bookIdOf(setOf("comics-prefetch")))
        assertEquals("prefetch:abc", WorkManagerTransferQueue.uniqueName("abc"))
    }
}
