package mx.jano.comics.cache

import mx.jano.comics.library.ReadState

/** Lo que mira la política para decidir qué desalojar. Puro: sin disco ni reloj. */
data class EvictionInput(
    val entries: List<CachedBook>,
    /** Fijados según el PinManager (además de [CachedBook.pinned]). */
    val pinned: Set<String>,
    /** Nunca se tocan: el libro abierto, su N+1, lo que se está bajando. */
    val protect: Set<String>,
    /** Bytes que ocupan las descargas parciales (.part): cuentan para el tope pero no se pueden desalojar aquí. */
    val partialBytes: Long,
    val limitBytes: Long,
    val freeBytes: Long,
    val minFreeBytes: Long,
    /** Lo que falta por bajar del libro que se quiere meter (0 al aplicar límites sin descargar nada). */
    val incomingBytes: Long = 0,
)

data class EvictionPlan(
    /** En orden de desalojo. */
    val evict: List<CachedBook>,
    /** Uso de la caché tras desalojar y meter lo que entra. */
    val usedAfter: Long,
    /** Espacio libre del volumen tras desalojar y meter lo que entra. */
    val freeAfter: Long,
    val limitOk: Boolean,
    val freeOk: Boolean,
) {
    val freedBytes: Long get() = evict.sumOf { it.bytes }
}

/**
 * Política de desalojo (LRU con prioridad). Se desaloja hasta que la caché quepa en el tope Y el volumen conserve el
 * margen libre, en este orden:
 *  1. terminados, del abierto hace más tiempo al más reciente;
 *  2. no empezados, igual;
 *  3. leyendo, igual.
 * Nunca un fijado ni un protegido. Si ni desalojando todo lo posible se cumple, el plan lo dice (limitOk / freeOk) y
 * quien llama decide según para qué baja ([DownloadPurpose]).
 */
object EvictionPolicy {
    fun rank(state: ReadState?): Int = when (state) {
        ReadState.FINISHED -> 0
        ReadState.UNREAD, null -> 1
        ReadState.READING -> 2
    }

    fun candidates(input: EvictionInput, readState: (CachedBook) -> ReadState?): List<CachedBook> =
        input.entries
            .filter { !it.pinned && it.bookId !in input.pinned && it.bookId !in input.protect }
            .sortedWith(compareBy<CachedBook>({ rank(readState(it)) }, { it.lastOpened }, { it.bookId }))

    fun plan(input: EvictionInput, readState: (CachedBook) -> ReadState?): EvictionPlan {
        var used = input.entries.sumOf { it.bytes } + input.partialBytes + input.incomingBytes
        var free = input.freeBytes - input.incomingBytes
        val evict = ArrayList<CachedBook>()
        for (candidate in candidates(input, readState)) {
            if (used <= input.limitBytes && free >= input.minFreeBytes) break
            evict += candidate
            used -= candidate.bytes
            free += candidate.bytes
        }
        return EvictionPlan(
            evict = evict,
            usedAfter = used,
            freeAfter = free,
            limitOk = used <= input.limitBytes,
            freeOk = free >= input.minFreeBytes,
        )
    }

    /** ¿Se puede bajar con este plan? (después de desalojar lo que diga). */
    fun allows(plan: EvictionPlan, purpose: DownloadPurpose, readFloorBytes: Long): Boolean = when (purpose) {
        DownloadPurpose.READ -> plan.freeAfter >= readFloorBytes
        DownloadPurpose.PIN -> plan.freeOk
        DownloadPurpose.PREFETCH -> plan.freeOk && plan.limitOk
    }
}
