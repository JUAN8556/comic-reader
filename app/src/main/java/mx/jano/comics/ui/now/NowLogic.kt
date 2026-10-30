package mx.jano.comics.ui.now

import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress

/** Un libro en curso con su progreso. */
data class InProgress(val book: Book, val progress: ReadingProgress)

data class NowLists(val continueReading: List<InProgress>, val next: List<Book>) {
    val isEmpty: Boolean get() = continueReading.isEmpty() && next.isEmpty()
}

object NowLogic {
    /**
     * Las entradas con actividad (en curso o leídas; no las «No leído»), de la más reciente a la más antigua, sin
     * tope: el progreso entero ya está en memoria ([mx.jano.comics.library.ProgressStore.all]). Pasar por
     * `recent(limit)` aquí perdía los libros a medias en cuanto había más «Leído» recientes que el tope (p. ej. tras
     * «Marcar todo como leído» en una serie larga).
     */
    fun withActivity(all: Collection<ReadingProgress>): List<ReadingProgress> =
        all.filter { it.state != ReadState.UNREAD }.sortedByDescending { it.updatedAt }

    /** Solo las entradas en curso (empezadas y sin terminar), de la más reciente a la más antigua. */
    fun inProgress(all: Collection<ReadingProgress>): List<ReadingProgress> =
        all.filter { !it.finished && it.page > 0 }.sortedByDescending { it.updatedAt }

    /**
     * «Continuar»: los libros en curso, del más reciente al más antiguo.
     * «Siguientes»: por cada serie (carpeta) empezada, el primer libro sin leer tras el último abierto (vía nextAfter),
     * saltando los ya leídos y los que están en «Continuar».
     */
    fun compute(
        recent: List<ReadingProgress>,
        bookByKey: (String) -> Book?,
        stateOf: (Book) -> ReadState,
        nextAfter: (Book) -> Book?,
        maxNext: Int = 24,
        maxWalk: Int = 2000,
    ): NowLists {
        val ordered = recent.sortedByDescending { it.updatedAt }
        val continueReading = ordered
            .filter { !it.finished && it.page > 0 }
            .mapNotNull { p -> bookByKey(p.contentKey)?.let { InProgress(it, p) } }
            .distinctBy { it.book.id }
        val inContinue = continueReading.mapTo(HashSet()) { it.book.id }

        val seenFolders = HashSet<String>()
        val next = mutableListOf<Book>()
        for (p in ordered) {
            if (next.size >= maxNext) break
            val book = bookByKey(p.contentKey) ?: continue
            if (!seenFolders.add(book.folderId)) continue
            var candidate = nextAfter(book)
            var steps = 0
            while (candidate != null && steps < maxWalk &&
                (candidate.id in inContinue || stateOf(candidate) != ReadState.UNREAD)
            ) {
                candidate = nextAfter(candidate)
                steps++
            }
            if (candidate != null && candidate.id !in inContinue && stateOf(candidate) == ReadState.UNREAD) {
                next += candidate
            }
        }
        return NowLists(continueReading, next.distinctBy { it.id })
    }
}
