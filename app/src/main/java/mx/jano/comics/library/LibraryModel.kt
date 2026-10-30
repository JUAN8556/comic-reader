package mx.jano.comics.library

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.settings.SettingsStore
import java.io.File

/**
 * CONTRATO COMÚN del MVP (firmas intocables). La biblioteca es un espejo de la carpeta raíz de Drive.
 * Índice en memoria (StateFlow) y en files/index.json; sin Room.
 */
@Serializable
data class Book(
    val id: String,                 // id de Drive
    val name: String,               // nombre de fichero tal cual
    val folderId: String,
    val path: String,               // ruta relativa desde la raíz, con «/» (sin el nombre de la raíz)
    val size: Long,
    val md5: String? = null,
    val headRevisionId: String? = null,
    val modifiedTime: String? = null,
    val mimeType: String = "",
) {
    /** Clave de contenido: sobrevive a mover, renombrar y resubir el mismo fichero. */
    val contentKey: String get() = "${md5 ?: id}:$size"
    val folderPath: String get() = path.substringBeforeLast('/', "")
}

@Serializable
data class Folder(
    val id: String,
    val name: String,
    val parentId: String?,          // null = raíz
    val path: String,               // «» para la raíz; «Americano/Marvel» para subcarpetas
    val bookCount: Int,             // directos
    val folderCount: Int,           // directas
    val totalBooks: Int,            // recursivo
)

@Serializable
data class LibraryIndex(
    val rootId: String,
    val rootName: String,
    val scannedAt: Long,
    val folders: List<Folder>,
    val books: List<Book>,
)

sealed interface ScanState {
    data object Idle : ScanState
    data class Scanning(val folders: Int, val books: Int) : ScanState
    data class Done(val folders: Int, val books: Int, val ms: Long) : ScanState
    data class Error(val message: String) : ScanState
}

interface LibraryRepository {
    val index: StateFlow<LibraryIndex?>
    val scanState: StateFlow<ScanState>
    /** Escaneo por niveles desde la raíz (lotes de padres, pageSize 1000, segundos). Ignora .indice y carpetas que empiezan por punto. */
    suspend fun rescan(): LibraryIndex
    fun folder(id: String): Folder?
    fun book(id: String): Book?
    /** Hijos directos en orden natural: carpetas primero, luego libros. */
    fun children(folderId: String): Pair<List<Folder>, List<Book>>
    /** Todos los libros bajo una carpeta, recursivo, en orden natural de ruta. */
    fun booksUnder(folderId: String): List<Book>
    /** Siguiente libro en orden natural dentro de la misma carpeta (N+1), o null. */
    fun nextAfter(book: Book): Book?
    fun previousBefore(book: Book): Book?
}

enum class ReadState { UNREAD, READING, FINISHED }

@Serializable
data class ReadingProgress(
    val contentKey: String,
    val page: Int,                  // 0-based, última página vista
    val pageCount: Int,
    val updatedAt: Long,
    val finished: Boolean,
) {
    val state: ReadState get() = when { finished -> ReadState.FINISHED; page > 0 -> ReadState.READING; else -> ReadState.UNREAD }
}

interface ProgressStore {
    val all: StateFlow<Map<String, ReadingProgress>>
    fun get(contentKey: String): ReadingProgress?
    fun stateOf(book: Book): ReadState
    suspend fun update(book: Book, page: Int, pageCount: Int)
    suspend fun setFinished(book: Book, finished: Boolean, pageCount: Int? = null)
    suspend fun setFinished(books: List<Book>, finished: Boolean)
    /** Libros con progreso, los más recientes primero. */
    fun recent(limit: Int): List<ReadingProgress>
    /** Copia en el appDataFolder de Drive (progress.json). */
    suspend fun backupToDrive(): Result<Unit>
    suspend fun restoreFromDrive(): Result<Int>
}

interface CoverStore {
    /** Portada ya disponible en disco, o null. */
    fun coverFile(book: Book): File?
    /** La consigue: del índice (.indice/portadas.zip importado), o por prefijo del archivo en Drive; null si no se pudo. */
    suspend fun ensureCover(book: Book): File?
    /** Importa .indice/portadas.zip a files/covers; devuelve cuántas. */
    suspend fun importIndexCovers(): Int
}

/** Los rellena el frente «data». */
object LibraryFactories {
    fun repository(context: Context, drive: DriveApi, settings: SettingsStore): LibraryRepository =
        LibraryWiring.repository(context, drive, settings)
    fun progressStore(context: Context, drive: DriveApi): ProgressStore =
        LibraryWiring.progressStore(context, LibraryWiring.appDataFor(drive))
    fun coverStore(context: Context, drive: DriveApi, library: LibraryRepository): CoverStore =
        LibraryWiring.coverStore(context, drive, library)
}

/** Orden natural y «smart naming» (puros, con pruebas JVM). Los rellena el frente «data». */
object Naming {
    /** Comparador natural: 1, 2, 10; insensible a mayúsculas y acentos. */
    val natural: Comparator<String> = NaturalComparator
    /** «Uncanny X-Men 174, October 1983.cbr» dentro del estante «Uncanny X-Men» → «174, October 1983». Sin extensión. */
    fun smartName(fileName: String, folderName: String): String = SmartNames.smartName(fileName, folderName)
}
