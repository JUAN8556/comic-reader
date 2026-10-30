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
