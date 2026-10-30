package mx.jano.comics.ui.search

import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.library.LibraryIndex
import mx.jano.comics.metadata.SeriesMeta
import mx.jano.comics.ui.TextNorm
import mx.jano.comics.ui.UiNaming

/**
 * Nombres ya normalizados de un índice: se calcula una vez por índice y sirve para todas las consultas. Una carpeta
 * también se encuentra por [seriesText]: lo que dice su ficha de serie (título, título original, guion, dibujo,
 * editorial), si la tiene.
 */
class SearchCorpus(index: LibraryIndex, seriesText: (Folder) -> String? = { null }) {
    internal val folders: List<Pair<String, Folder>> =
        index.folders.filter { it.parentId != null }.map { f ->
            val extra = runCatching { seriesText(f) }.getOrNull().orEmpty()
            TextNorm.normalize(if (extra.isBlank()) f.name else "${f.name} $extra") to f
        }
    internal val books: List<Pair<String, Book>> =
        index.books.map { TextNorm.normalize(UiNaming.stripExtension(it.name)) to it }
    internal val folderById: Map<String, Folder> = index.folders.associateBy { it.id }
}

/** Libros de una misma carpeta en los resultados. */
data class BookGroup(val folderId: String, val folderPath: String, val folderName: String, val books: List<Book>)

data class SearchResults(
    val query: String,
    val folders: List<Folder>,
    val groups: List<BookGroup>,
    /** Libros que coinciden en total (aunque solo se muestren [shownBooks]). */
    val bookCount: Int,
    val shownBooks: Int,
) {
    val isEmpty: Boolean get() = folders.isEmpty() && groups.isEmpty()
    val truncated: Boolean get() = shownBooks < bookCount

    companion object {
        val EMPTY = SearchResults("", emptyList(), emptyList(), 0, 0)
    }
}

object SearchLogic {
    const val MAX_BOOKS = 400

    /** Lo buscable de una ficha de serie: títulos, serie canónica, guion, dibujo y editoriales. */
    fun seriesText(meta: SeriesMeta): String =
        (listOfNotNull(meta.titulo, meta.titulo_original, meta.serie_canonica, meta.editorial_original, meta.editorial_edicion) +
            meta.guion + meta.dibujo).joinToString(" ")

    /** Coincide si TODAS las palabras de la consulta aparecen en el nombre (sin acentos ni mayúsculas). */
    fun matches(normalizedName: String, tokens: List<String>): Boolean =
        tokens.isNotEmpty() && tokens.all { normalizedName.contains(it) }

    /**
     * Carpetas cuyo nombre coincide (por ruta, en orden natural) y libros agrupados por su carpeta (grupos por ruta,
     * libros por nombre, ambos en orden natural). La raíz no sale como carpeta.
     */
    fun search(
        corpus: SearchCorpus,
        query: String,
        comparator: Comparator<String>,
        maxBooks: Int = MAX_BOOKS,
    ): SearchResults {
        val tokens = TextNorm.tokens(query)
        if (tokens.isEmpty()) return SearchResults.EMPTY.copy(query = query)
        val folders = corpus.folders.asSequence()
            .filter { matches(it.first, tokens) }
            .map { it.second }
            .sortedWith(compareBy(comparator) { it.path })
            .toList()
        val books = corpus.books.asSequence()
            .filter { matches(it.first, tokens) }
            .map { it.second }
            .toList()
        val shown = books
            .sortedWith(compareBy<Book, String>(comparator) { it.folderPath }.thenBy(comparator) { it.name })
            .take(maxBooks)
        val groups = shown.groupBy { it.folderId }.map { (folderId, list) ->
            val folder = corpus.folderById[folderId]
            val path = folder?.path ?: list.first().folderPath
            BookGroup(
                folderId = folderId,
                folderPath = path,
                folderName = folder?.name ?: path.substringAfterLast('/'),
                books = list,
            )
        }
        return SearchResults(query, folders, groups, bookCount = books.size, shownBooks = shown.size)
    }
}
