package mx.jano.comics.ui.fakes

import mx.jano.comics.library.Book
import mx.jano.comics.library.Folder
import mx.jano.comics.library.LibraryIndex
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.metadata.SeriesMeta

/** Biblioteca de muestra (inventada) con las seis categorías de primer nivel. */
object FakeData {
    const val ROOT = "root-comics"

    fun index(): LibraryIndex {
        val folders = mutableListOf<Folder>()
        val books = mutableListOf<Book>()
        fun folder(id: String, name: String, parent: String?, path: String) {
            folders += Folder(id, name, parent, path, bookCount = 0, folderCount = 0, totalBooks = 0)
        }
        fun series(id: String, name: String, parentId: String, parentPath: String, files: List<String>) {
            val path = "$parentPath/$name"
            folder(id, name, parentId, path)
            files.forEachIndexed { i, file ->
                books += Book(
                    id = "$id-b${i + 1}",
                    name = file,
                    folderId = id,
                    path = "$path/$file",
                    size = 30L * 1024 * 1024 + i,
                    md5 = "md5-$id-${i + 1}",
                )
            }
        }
        folder(ROOT, "Cómics", null, "")
        for ((id, name) in listOf(
            "americano" to "Americano", "europeo" to "Europeo", "latino" to "Latinoamericano",
            "manga" to "Manga", "manhwa" to "Manhwa", "manhua" to "Manhua",
        )) {
            folder(id, name, ROOT, name)
        }
        folder("marvel", "Marvel", "americano", "Americano/Marvel")
        series("xmen", "Uncanny X-Men", "marvel", "Americano/Marvel", (1..12).map { "Uncanny X-Men $it.cbr" })
        series("asterix", "Astérix", "europeo", "Europeo", (1..5).map { "Astérix $it.cbz" })
        series("mafalda", "Mafalda", "latino", "Latinoamericano", (1..3).map { "Mafalda $it.cbz" })
        series("berserk", "Berserk", "manga", "Manga", (1..8).map { "Berserk v$it.cbz" })
        return LibraryIndex(ROOT, "Cómics", scannedAt = 0, folders = recount(folders, books), books = books)
    }

    private fun recount(folders: List<Folder>, books: List<Book>): List<Folder> = folders.map { f ->
        val prefix = if (f.path.isEmpty()) "" else f.path + "/"
        f.copy(
            bookCount = books.count { it.folderId == f.id },
            folderCount = folders.count { it.parentId == f.id },
            totalBooks = books.count { it.path.startsWith(prefix) },
        )
    }

    fun progress(index: LibraryIndex): Map<String, ReadingProgress> {
        fun key(id: String) = index.books.first { it.id == id }.contentKey
        val now = 1_761_900_000_000L
        return listOf(
            ReadingProgress(key("xmen-b1"), 35, 36, now - 86_400_000, finished = true),
            ReadingProgress(key("xmen-b2"), 9, 36, now - 3_600_000, finished = false),
            ReadingProgress(key("berserk-b1"), 120, 224, now - 600_000, finished = false),
            ReadingProgress(key("asterix-b1"), 47, 48, now - 5 * 86_400_000L, finished = true),
        ).associateBy { it.contentKey }
    }

    fun series(): Map<String, SeriesMeta> = mapOf(
        "Manga/Berserk" to SeriesMeta(
            ruta = "Manga/Berserk",
            titulo = "Berserk",
            titulo_original = "ベルセルク",
            tipo = "Manga",
            categoria = "Manga",
            guion = listOf("Kentaro Miura"),
            dibujo = listOf("Kentaro Miura"),
            editorial_original = "Hakusensha",
            editorial_edicion = "Panini",
            anios = "1989-",
            generos = listOf("Fantasía oscura", "Seinen"),
            estado = "En curso",
            numeros_totales = 10,
            numeros_en_carpeta = 8,
            sinopsis = "Ficha de muestra para las vistas previas.",
            fuentes = listOf("https://es.wikipedia.org/wiki/Berserk"),
            confianza = "media",
        ),
    )
}
