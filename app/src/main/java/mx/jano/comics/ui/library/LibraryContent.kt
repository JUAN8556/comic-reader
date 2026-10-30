package mx.jano.comics.ui.library

import mx.jano.comics.cache.Availability
import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.ui.UiNaming
import mx.jano.comics.ui.now.InProgress

/** Una carpeta en la rejilla: sus cuentas recursivas y los libros de su pila de portadas. */
data class FolderTile(val folder: Folder, val stats: FolderStats, val coverBooks: List<Book>)

/** Un libro en la rejilla, con su nombre inteligente y su estado. */
data class BookTile(val book: Book, val displayName: String, val status: BookStatus)

/** Lo que pinta la biblioteca para una carpeta, ya filtrado. */
data class FolderContent(
    val folderId: String,
    val folder: Folder?,
    /** De la raíz a la carpeta actual, ambas incluidas. */
    val crumbs: List<Folder>,
    val folders: List<FolderTile>,
    val books: List<BookTile>,
    /** Libro cuya portada, difuminada, hace de fondo. */
    val backdrop: Book?,
    /** Hijos directos antes de filtrar (para distinguir «vacía» de «nada con este filtro»). */
    val totalChildren: Int,
    /** Solo en la raíz: los libros en curso para la fila «Continuar». */
    val continueReading: List<InProgress>,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && books.isEmpty()
}

object LibraryContentBuilder {
    /**
     * Libros para la pila de una carpeta: los primeros que ya tienen portada en disco (mirando los [probe] primeros),
     * completados con los siguientes para que la pila tenga 3 si hay libros.
     */
    fun pickCoverBooks(under: List<Book>, hasCover: (Book) -> Boolean, probe: Int = 12): List<Book> {
        val head = under.take(probe)
        val withCover = head.filter(hasCover).take(3)
        return (withCover + head.filterNot { it in withCover }).take(3)
    }

    /** De la raíz a [folder], siguiendo parentId (con tope por si el índice tuviera un ciclo). */
    fun crumbs(folder: Folder?, folderOf: (String) -> Folder?): List<Folder> {
        val out = ArrayList<Folder>()
        var f = folder
        var guard = 0
        while (f != null && guard++ < 64) {
            out.add(0, f)
            f = f.parentId?.let(folderOf)
        }
        return out
    }

    fun build(
        library: LibraryRepository,
        folderId: String,
        isRoot: Boolean,
        progress: Map<String, ReadingProgress>,
        filter: LibraryFilter,
        availabilityOf: (Book) -> Availability,
        hasCover: (Book) -> Boolean,
        recent: () -> List<ReadingProgress>,
        bookByKey: (String) -> Book?,
        /** Cuántos libros como mucho en «Continuar» (se cuenta tras quitar los que ya no están en el índice). */
        continueLimit: Int = Int.MAX_VALUE,
    ): FolderContent {
        val folder = library.folder(folderId)
        val (childFolders, childBooks) = library.children(folderId)
        val stateOf = { b: Book -> LibraryLogic.stateOf(progress[b.contentKey]) }

        val folderTiles = childFolders.map { f ->
            val under = library.booksUnder(f.id)
            FolderTile(f, LibraryLogic.stats(under, stateOf, availabilityOf), pickCoverBooks(under, hasCover))
        }
        val folderName = folder?.name.orEmpty()
        val bookTiles = childBooks.map { b ->
            BookTile(b, UiNaming.smartName(b.name, folderName), LibraryLogic.statusOf(progress[b.contentKey]))
        }
        val backdrop = childBooks.take(12).firstOrNull(hasCover)
            ?: folderTiles.firstNotNullOfOrNull { t -> t.coverBooks.firstOrNull(hasCover) }
            ?: childBooks.firstOrNull()
            ?: folderTiles.firstNotNullOfOrNull { it.coverBooks.firstOrNull() }

        val continueReading = if (isRoot) {
            recent()
                .filter { !it.finished && it.page > 0 }
                .mapNotNull { p -> bookByKey(p.contentKey)?.let { InProgress(it, p) } }
                .distinctBy { it.book.id }
                .take(continueLimit)
        } else {
            emptyList()
        }

        return FolderContent(
            folderId = folderId,
            folder = folder,
            crumbs = crumbs(folder, library::folder),
            folders = folderTiles.filter { LibraryLogic.folderVisible(filter, it.stats) },
            books = bookTiles.filter { LibraryLogic.matches(filter, stateOf(it.book), availabilityOf(it.book)) },
            backdrop = backdrop,
            totalChildren = childFolders.size + childBooks.size,
            continueReading = continueReading,
        )
    }
}
