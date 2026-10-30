package mx.jano.comics.ui.library

import kotlinx.serialization.Serializable
import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.ui.UiFormat
import mx.jano.comics.ui.UiStrings

/** Botón de filtro de la biblioteca. */
@Serializable
enum class LibraryFilter { ALL, TO_READ, READING, READ, OFFLINE }

/** Marca agregada de una carpeta: visto (Check) todo leído, medio círculo en curso, nada sin empezar. */
enum class FolderMark { NONE, IN_PROGRESS, ALL_READ }

/** Cuentas de los libros de una carpeta (recursivas). */
data class FolderStats(
    val total: Int,
    val read: Int,
    val reading: Int,
    val unread: Int,
    /** En la tablet (descargados o fijados): legibles sin conexión. */
    val offline: Int,
) {
    val mark: FolderMark
        get() = when {
            total > 0 && read == total -> FolderMark.ALL_READ
            read > 0 || reading > 0 -> FolderMark.IN_PROGRESS
            else -> FolderMark.NONE
        }

    fun count(filter: LibraryFilter): Int = when (filter) {
        LibraryFilter.ALL -> total
        LibraryFilter.TO_READ -> unread
        LibraryFilter.READING -> reading
        LibraryFilter.READ -> read
        LibraryFilter.OFFLINE -> offline
    }

    companion object {
        val EMPTY = FolderStats(0, 0, 0, 0, 0)
    }
}

/** Estado de un libro tal como se pinta: «No leído», «Pág. 10 de 36» (con barra) o «Leído». */
sealed interface BookStatus {
    data object Unread : BookStatus
    data class Reading(val page: Int, val pageCount: Int) : BookStatus {
        /** Avance 0..1 para la barra. */
        val fraction: Float get() = if (pageCount <= 0) 0f else (page.toFloat() / pageCount).coerceIn(0f, 1f)
    }
    data object Finished : BookStatus
}

object LibraryLogic {
    fun isOffline(a: Availability): Boolean = a == Availability.LOCAL || a == Availability.PINNED

    fun stateOf(p: ReadingProgress?): ReadState = p?.state ?: ReadState.UNREAD

    fun matches(filter: LibraryFilter, state: ReadState, availability: Availability): Boolean = when (filter) {
        LibraryFilter.ALL -> true
        LibraryFilter.TO_READ -> state == ReadState.UNREAD
        LibraryFilter.READING -> state == ReadState.READING
        LibraryFilter.READ -> state == ReadState.FINISHED
        LibraryFilter.OFFLINE -> isOffline(availability)
    }

    fun stats(books: List<Book>, stateOf: (Book) -> ReadState, availabilityOf: (Book) -> Availability): FolderStats {
        var read = 0
        var reading = 0
        var unread = 0
        var offline = 0
        for (b in books) {
            when (stateOf(b)) {
                ReadState.FINISHED -> read++
                ReadState.READING -> reading++
                ReadState.UNREAD -> unread++
            }
            if (isOffline(availabilityOf(b))) offline++
        }
        return FolderStats(books.size, read, reading, unread, offline)
    }

    /** Una carpeta se ve con un filtro si tiene algún libro que lo cumpla; con «Todos», siempre (aunque esté vacía). */
    fun folderVisible(filter: LibraryFilter, stats: FolderStats): Boolean =
        filter == LibraryFilter.ALL || stats.count(filter) > 0

    fun statusOf(p: ReadingProgress?): BookStatus = when {
        p == null -> BookStatus.Unread
        p.finished -> BookStatus.Finished
        p.page > 0 -> BookStatus.Reading(page = p.page + 1, pageCount = p.pageCount)
        else -> BookStatus.Unread
    }

    fun statusText(s: BookStatus): String = when (s) {
        BookStatus.Unread -> UiStrings.STATUS_UNREAD
        BookStatus.Finished -> UiStrings.STATUS_READ
        is BookStatus.Reading -> UiStrings.statusPage(s.page, s.pageCount)
    }

    /** «30 cómics · 12 leídos», «30 cómics · todos leídos», «30 cómics · 1 en curso», «30 cómics» o «Vacía». */
    fun folderSummary(s: FolderStats): String = when {
        s.total == 0 -> UiStrings.FOLDER_EMPTY
        s.read == s.total -> UiFormat.comics(s.total) + " · " +
            if (s.total == 1) UiStrings.FOLDER_ALL_READ_ONE else UiStrings.FOLDER_ALL_READ
        s.read > 0 -> UiFormat.comics(s.total) + " · " + UiStrings.folderRead(s.read)
        s.reading > 0 -> UiFormat.comics(s.total) + " · " + UiStrings.folderInProgress(s.reading)
        else -> UiFormat.comics(s.total)
    }

    fun filterLabel(f: LibraryFilter): String = when (f) {
        LibraryFilter.ALL -> UiStrings.FILTER_ALL
        LibraryFilter.TO_READ -> UiStrings.FILTER_TO_READ
        LibraryFilter.READING -> UiStrings.FILTER_READING
        LibraryFilter.READ -> UiStrings.FILTER_READ
        LibraryFilter.OFFLINE -> UiStrings.FILTER_OFFLINE
    }

    /**
     * Columnas de la rejilla según la clase de ancho de la ventana: compacta 3; media (la tablet en vertical,
     * 720 dp) 4-5; expandida (en horizontal, 1100 dp) 7-8.
     */
    fun gridColumns(widthDp: Int, atLeastMedium: Boolean, atLeastExpanded: Boolean): Int = when {
        !atLeastMedium -> 3
        !atLeastExpanded -> if (widthDp >= 700) 5 else 4
        else -> if (widthDp >= 1080) 8 else 7
    }
}
