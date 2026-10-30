package mx.jano.comics.cache

import mx.jano.comics.library.ReadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvictionPolicyTest {

    private fun entry(id: String, bytes: Long, lastOpened: Long, pinned: Boolean = false) =
        CachedBook(bookId = id, contentKey = "md5-$id:$bytes", file = "/books/$id.cbz", bytes = bytes, lastOpened = lastOpened, pinned = pinned)

    private fun input(
        entries: List<CachedBook>,
        limit: Long = 10_000,
        free: Long = 1_000_000,
        minFree: Long = 0,
        incoming: Long = 0,
        pinned: Set<String> = emptySet(),
        protect: Set<String> = emptySet(),
        partial: Long = 0,
    ) = EvictionInput(entries, pinned, protect, partial, limit, free, minFree, incoming)

    @Test
    fun `dentro del tope y con margen no desaloja nada`() {
        val plan = EvictionPolicy.plan(input(listOf(entry("a", 3_000, 1), entry("b", 3_000, 2))), { null })
        assertTrue(plan.evict.isEmpty())
        assertTrue(plan.limitOk && plan.freeOk)
        assertEquals(6_000, plan.usedAfter)
    }

    @Test
    fun `terminados antes que no empezados y estos antes que leyendo, aunque sean mas recientes`() {
        val entries = listOf(
            entry("leyendo-viejo", 3_000, lastOpened = 1),
            entry("sin-empezar", 3_000, lastOpened = 2),
            entry("terminado-reciente", 3_000, lastOpened = 9),
            entry("terminado-viejo", 3_000, lastOpened = 3),
        )
        val states = mapOf(
            "leyendo-viejo" to ReadState.READING,
            "sin-empezar" to ReadState.UNREAD,
            "terminado-reciente" to ReadState.FINISHED,
            "terminado-viejo" to ReadState.FINISHED,
        )
        val order = EvictionPolicy.candidates(input(entries), { states[it.bookId] }).map { it.bookId }
        assertEquals(listOf("terminado-viejo", "terminado-reciente", "sin-empezar", "leyendo-viejo"), order)

        // 12 000 en un tope de 5 000: hay que quitar 7 000 → tres libros de 3 000.
        val plan = EvictionPolicy.plan(input(entries, limit = 5_000), { states[it.bookId] })
        assertEquals(listOf("terminado-viejo", "terminado-reciente", "sin-empezar"), plan.evict.map { it.bookId })
        assertTrue(plan.limitOk)
        assertEquals(9_000, plan.freedBytes) // quedan 3 000
    }

    @Test
    fun `sin progreso conocido es LRU puro`() {
        val entries = listOf(entry("c", 1_000, 30), entry("a", 1_000, 10), entry("b", 1_000, 20))
        val plan = EvictionPolicy.plan(input(entries, limit = 1_500), { null })
        assertEquals(listOf("a", "b"), plan.evict.map { it.bookId })
    }

    @Test
    fun `nunca toca fijados ni protegidos aunque no quepa`() {
        val entries = listOf(
            entry("fijado-flag", 4_000, 1, pinned = true),
            entry("fijado-set", 4_000, 2),
            entry("abierto", 4_000, 3),
            entry("siguiente", 4_000, 4),
            entry("libre", 4_000, 5),
        )
        val plan = EvictionPolicy.plan(
            input(entries, limit = 1_000, pinned = setOf("fijado-set"), protect = setOf("abierto", "siguiente")),
            { ReadState.FINISHED },
        )
        assertEquals(listOf("libre"), plan.evict.map { it.bookId })
        assertFalse(plan.limitOk)
        assertEquals(16_000, plan.usedAfter)
    }

    @Test
    fun `el margen libre tambien obliga a desalojar`() {
        // Tope holgado, pero el volumen solo tiene 18 000 libres y hay que dejar 15 000 tras meter 5 000.
        val entries = listOf(entry("a", 2_000, 1), entry("b", 2_000, 2), entry("c", 2_000, 3))
        val plan = EvictionPolicy.plan(input(entries, limit = 1_000_000, free = 18_000, minFree = 15_000, incoming = 5_000), { null })
        assertEquals(listOf("a"), plan.evict.map { it.bookId })
        assertEquals(15_000, plan.freeAfter)
        assertTrue(plan.freeOk)
    }

    @Test
    fun `los parciales cuentan para el tope`() {
        val entries = listOf(entry("a", 4_000, 1), entry("b", 4_000, 2))
        val plan = EvictionPolicy.plan(input(entries, limit = 10_000, partial = 3_000), { null })
        assertEquals(listOf("a"), plan.evict.map { it.bookId })
    }

    @Test
    fun `cada motivo de descarga aprieta distinto`() {
        val overLimitOnly = EvictionPlan(emptyList(), usedAfter = 30_000, freeAfter = 50_000, limitOk = false, freeOk = true)
        val noMargin = EvictionPlan(emptyList(), usedAfter = 1_000, freeAfter = 9_000, limitOk = true, freeOk = false)
        val belowFloor = EvictionPlan(emptyList(), usedAfter = 1_000, freeAfter = 50, limitOk = true, freeOk = false)

        assertTrue(EvictionPolicy.allows(overLimitOnly, DownloadPurpose.READ, readFloorBytes = 100))
        assertTrue(EvictionPolicy.allows(overLimitOnly, DownloadPurpose.PIN, readFloorBytes = 100))
        assertFalse(EvictionPolicy.allows(overLimitOnly, DownloadPurpose.PREFETCH, readFloorBytes = 100))

        assertTrue(EvictionPolicy.allows(noMargin, DownloadPurpose.READ, readFloorBytes = 100))
        assertFalse(EvictionPolicy.allows(noMargin, DownloadPurpose.PIN, readFloorBytes = 100))
        assertFalse(EvictionPolicy.allows(noMargin, DownloadPurpose.PREFETCH, readFloorBytes = 100))

        assertFalse(EvictionPolicy.allows(belowFloor, DownloadPurpose.READ, readFloorBytes = 100))
    }
}
