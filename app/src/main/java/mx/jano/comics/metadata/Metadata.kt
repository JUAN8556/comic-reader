package mx.jano.comics.metadata

import android.content.Context
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.library.Book
import mx.jano.comics.library.LibraryRepository

/**
 * CONTRATO COMÚN del MVP. Lo que la migración deja en Cómics/.indice/: indice.jsonl (una línea por fichero),
 * series.jsonl (ficha por serie, «nivel 2»), taxonomia.json, portadas.zip. Todo tolerante a campos ausentes.
 */
@Serializable
data class IndexEntry(
    val ruta: String,               // ruta relativa en Drive bajo Cómics/, NFC
    val md5: String? = null,
    val size: Long = 0,
    val tipo: String? = null,       // ZIP, RAR4, RAR4_SOLID, RAR5, SEVEN_ZIP, PDF
    val paginas: Int? = null,
    val serie: String? = null,
    val numero: String? = null,
    val anio: Int? = null,
    val portada: String? = null,    // nombre dentro de portadas.zip
    val ruta_original: String? = null,
)

@Serializable
data class SeriesMeta(
    val ruta: String,
    val titulo: String? = null,
    val titulo_original: String? = null,
    val tipo: String? = null,
    val categoria: String? = null,
    val editorial_carpeta: String? = null,
    val serie_canonica: String? = null,
    val pais: String? = null,
    val idioma_edicion: String? = null,
    val editorial_original: String? = null,
    val editorial_edicion: String? = null,
    val guion: List<String> = emptyList(),
    val dibujo: List<String> = emptyList(),
    val anios: String? = null,
    val generos: List<String> = emptyList(),
    val estado: String? = null,
    val numeros_totales: Int? = null,
    val numeros_en_carpeta: Int? = null,
    val sinopsis: String? = null,
    val fuentes: List<String> = emptyList(),
    val confianza: String? = null,  // alta | media | baja
    val notas: String? = null,
)

interface MetadataRepository {
    /** Baja los ficheros de .indice si cambiaron (por md5/headRevisionId) y lo carga; silencioso si no existe. */
    suspend fun refreshFromDrive(): Result<Unit>
    fun entryFor(book: Book): IndexEntry?
    /** Ficha de la serie a la que pertenece una carpeta (ruta de carpeta en Drive), o null. */
    fun seriesFor(folderPath: String): SeriesMeta?
    /** Números que faltan en la carpeta según numeros_totales y los números presentes. */
    fun missingNumbers(folderPath: String, books: List<Book>): List<Int>
    val loaded: Boolean
}

/** Lo rellena el frente «data». */
object MetadataFactories {
    /** Una instancia por proceso. La raíz sale del índice de [library] o, si aún no hay, de los ajustes. */
    fun repository(context: Context, drive: DriveApi, library: LibraryRepository): MetadataRepository =
        MetadataWiring.repository(context, drive, library)
}
