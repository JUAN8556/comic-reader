package mx.jano.comics.metadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import mx.jano.comics.library.Book
import mx.jano.comics.library.SmartNames
import mx.jano.comics.settings.PathKeys
import java.io.File
import java.io.IOException

/** Lo cargado de `.indice`, inmutable (se sustituye entero al recargar). */
internal class MetadataData(
    entries: List<IndexEntry>,
    series: List<SeriesMeta>,
    val taxonomy: JsonElement?,
    val skippedLines: Int,
) {
    val entriesByPath: Map<String, IndexEntry> = entries.associateBy { PathKeys.normalize(it.ruta) }
    val entriesByMd5: Map<String, List<IndexEntry>> = entries.filter { !it.md5.isNullOrBlank() }.groupBy { it.md5!!.lowercase() }
    val seriesByPath: Map<String, SeriesMeta> = series.associateBy { PathKeys.normalize(it.ruta) }
    val entryCount: Int = entries.size
    val seriesCount: Int = series.size

    companion object {
        val EMPTY = MetadataData(emptyList(), emptyList(), null, 0)
    }
}

/**
 * [MetadataRepository] sobre `Cómics/.indice/` (indice.jsonl, series.jsonl, taxonomia.json).
 *
 * - Al construirse carga en segundo plano lo último bajado (files/indice): la ficha funciona sin conexión.
 * - [refreshFromDrive] baja solo lo que cambió (md5) y recarga; si `.indice` no existe o no hay raíz, no hace nada
 *   y devuelve éxito. Las líneas rotas se saltan ([skippedLines]); un campo que no encaja se descarta, no la línea.
 * - [entryFor]: por ruta (NFC) y, si no, por md5 (el mismo fichero movido o renombrado), prefiriendo el de igual tamaño.
 * - [seriesFor]: la ficha de esa carpeta o de la más cercana hacia arriba.
 */
class DriveMetadataRepository internal constructor(
    private val dir: File,
    private val sync: IndiceSync,
    scope: CoroutineScope,
    private val log: (String) -> Unit = {},
) : MetadataRepository {

    @Volatile private var data: MetadataData = MetadataData.EMPTY
    @Volatile private var loadedFlag = false
    private val mutex = Mutex()
    private val initialLoad: Job = scope.launch(Dispatchers.IO) { mutex.withLock { loadFromDisk() } }

    override val loaded: Boolean get() = loadedFlag

    /** Líneas que no se pudieron leer en la última carga (diagnóstico). */
    val skippedLines: Int get() = data.skippedLines
    val entryCount: Int get() = data.entryCount
    val seriesCount: Int get() = data.seriesCount

    /** taxonomia.json tal cual (su forma la decide la migración), o null. */
    val taxonomy: JsonElement? get() = data.taxonomy

    /** Espera a la carga inicial desde disco. */
    suspend fun awaitInitialLoad() = initialLoad.join()

    override suspend fun refreshFromDrive(): Result<Unit> {
        initialLoad.join()
        return try {
            mutex.withLock {
                val changed = sync.sync(setOf(IndiceSync.INDICE, IndiceSync.SERIES, IndiceSync.TAXONOMIA))
                when {
                    changed == null -> log("metadatos: no hay .indice en Drive")
                    changed.isNotEmpty() || !loadedFlag -> withContext(Dispatchers.IO) { loadFromDisk() }
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("metadatos: ${e::class.java.simpleName}: ${e.message}")
            Result.failure(IOException(MetadataStrings.refreshFailed(e.message), e))
        }
    }

    override fun entryFor(book: Book): IndexEntry? {
        val d = data
        d.entriesByPath[PathKeys.normalize(book.path)]?.let { return it }
        val md5 = book.md5?.lowercase() ?: return null
        val candidates = d.entriesByMd5[md5] ?: return null
        return candidates.firstOrNull { it.size == book.size } ?: candidates.first()
    }

    override fun seriesFor(folderPath: String): SeriesMeta? {
        val d = data
        if (d.seriesByPath.isEmpty()) return null
        for (candidate in PathKeys.selfAndAncestors(PathKeys.normalize(folderPath))) {
            d.seriesByPath[candidate]?.let { return it }
        }
        return null
    }

    /**
     * Números de 1 a `numeros_totales` que no están entre [books]. Solo con la ficha de ESA carpeta (no la de un
     * antepasado: sus números estarían repartidos en otras carpetas) y si al menos un libro dice su número; el
     * número sale del índice (`numero`) o del nombre del fichero ([SmartNames.issueNumber]).
     */
    override fun missingNumbers(folderPath: String, books: List<Book>): List<Int> {
        val key = PathKeys.normalize(folderPath)
        val meta = data.seriesByPath[key] ?: return emptyList()
        val total = meta.numeros_totales ?: return emptyList()
        if (total <= 0 || total > MAX_TOTAL || books.isEmpty()) return emptyList()
        val folderName = key.substringAfterLast('/')
        val present = books.mapNotNullTo(HashSet()) { b ->
            TolerantJson.firstInt(entryFor(b)?.numero) ?: SmartNames.issueNumber(b.name, folderName)
        }
        if (present.isEmpty()) return emptyList()
        return (1..total).filter { it !in present }
    }

    private fun loadFromDisk() {
        val indice = sync.local(IndiceSync.INDICE)
        val series = sync.local(IndiceSync.SERIES)
        val taxonomy = sync.local(IndiceSync.TAXONOMIA)
        if (indice == null && series == null && taxonomy == null) {
            data = MetadataData.EMPTY
            loadedFlag = false
            return
        }
        val entries = indice?.let { readText(it) }?.let { TolerantJson.parseLines(it, IndexEntry.serializer()) }
        val metas = series?.let { readText(it) }?.let { TolerantJson.parseLines(it, SeriesMeta.serializer()) }
        val tax = taxonomy?.let { readText(it) }?.let {
            try {
                TolerantJson.json.parseToJsonElement(it)
            } catch (e: Exception) {
                log("metadatos: taxonomia.json ilegible: ${e.message}")
                null
            }
        }
        val skipped = (entries?.skipped ?: 0) + (metas?.skipped ?: 0)
        data = MetadataData(entries?.items.orEmpty(), metas?.items.orEmpty(), tax, skipped)
        loadedFlag = true
        log("metadatos: ${entries?.items?.size ?: 0} ficheros, ${metas?.items?.size ?: 0} series, $skipped líneas saltadas")
    }

    private fun readText(file: File): String? = try {
        file.readText(Charsets.UTF_8)
    } catch (e: IOException) {
        log("metadatos: no se pudo leer ${file.name}: ${e.message}")
        null
    }

    companion object {
        /** Más números que esto en una serie es un dato erróneo: no se calculan huecos. */
        const val MAX_TOTAL = 5000
    }
}
