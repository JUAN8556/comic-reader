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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    val seriesCount: Int = seriesByPath.size

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
    /** Huella (tamaño y fecha) de los ficheros de disco que se cargaron: si otro los bajó (las portadas), se recarga. */
    @Volatile private var loadedStamp: String = ""
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
                    changed.isNotEmpty() || !loadedFlag || diskStamp() != loadedStamp ->
                        withContext(Dispatchers.IO) { loadFromDisk() }
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

    private fun diskStamp(): String = listOf(IndiceSync.INDICE, IndiceSync.SERIES, IndiceSync.TAXONOMIA)
        .joinToString("|") { name -> sync.local(name)?.let { "${it.length()}@${it.lastModified()}" } ?: "-" }

    private fun loadFromDisk() {
        loadedStamp = diskStamp()
        val indice = sync.local(IndiceSync.INDICE)
        val series = sync.local(IndiceSync.SERIES)
        val taxonomy = sync.local(IndiceSync.TAXONOMIA)
        if (indice == null && series == null && taxonomy == null) {
            data = MetadataData.EMPTY
            loadedFlag = false
            return
        }
        val indiceText = indice?.let { readText(it) }
        val entries = indiceText?.let { TolerantJson.parseLines(it, IndexEntry.serializer()) }
        val fromIndex = indiceText?.let { seriesFromIndex(it) }.orEmpty()
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
        // Las de series.jsonl van detrás: si una casa con una carpeta de Drive, gana sobre la sacada del índice.
        data = MetadataData(entries?.items.orEmpty(), fromIndex + metas?.items.orEmpty(), tax, skipped)
        loadedFlag = true
        log(
            "metadatos: ${entries?.items?.size ?: 0} ficheros, ${metas?.items?.size ?: 0} series " +
                "(${fromIndex.size} carpetas con ficha desde el índice), $skipped líneas saltadas",
        )
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

        /**
         * Fichas por carpeta de Drive sacadas del campo `meta_serie` de indice.jsonl. La migración escribe la `ruta` de
         * series.jsonl con la carpeta de ORIGEN (iCloud), que no coincide con la de Drive (Categoría/Editorial/Serie);
         * cada línea del índice lleva la ficha de su serie, así que su carpeta de Drive es la del cómic. Manda la
         * primera línea de cada carpeta; una ficha rota se salta (lectura tolerante, como el resto).
         */
        internal fun seriesFromIndex(text: String): List<SeriesMeta> {
            val seen = HashSet<String>()
            val out = ArrayList<SeriesMeta>()
            text.lineSequence().forEach { raw ->
                val line = raw.trim().removePrefix("\uFEFF")
                if (line.isEmpty() || "meta_serie" !in line) return@forEach
                val obj = try {
                    TolerantJson.json.parseToJsonElement(line) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: return@forEach
                val ruta = (obj["ruta"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                val meta = obj["meta_serie"] as? JsonObject ?: return@forEach
                val folder = PathKeys.parent(PathKeys.normalize(ruta))?.takeIf { it.isNotEmpty() } ?: return@forEach
                if (!seen.add(folder)) return@forEach
                val withPath = JsonObject(meta + ("ruta" to JsonPrimitive(folder)))
                TolerantJson.decodeObject(withPath, SeriesMeta.serializer())?.let(out::add)
            }
            return out
        }
    }
}
