package mx.jano.comics.library

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.drive.DriveRestApi
import okhttp3.HttpUrl

/** Un elemento de Drive tal y como lo necesita el escáner (con `headRevisionId`, que [DriveFile] no trae). */
@Serializable
internal data class ScanItem(
    val id: String,
    val name: String,
    val mimeType: String = "",
    val size: Long? = null,
    val md5Checksum: String? = null,
    val headRevisionId: String? = null,
    val modifiedTime: String? = null,
    val parents: List<String>? = null,
) {
    val isFolder: Boolean get() = mimeType == FOLDER_MIME

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
        fun of(f: DriveFile, parent: String) = ScanItem(
            id = f.id, name = f.name, mimeType = f.mimeType, size = f.size, md5Checksum = f.md5Checksum,
            modifiedTime = f.modifiedTime, parents = f.parents ?: listOf(parent),
        )
    }
}

/** Hijos directos (sin papelera) de un lote de carpetas. Cada [ScanItem] trae sus `parents`. */
internal fun interface ChildLister {
    suspend fun list(parentIds: List<String>): List<ScanItem>
}

/** Lee un trozo de un fichero de Drive sin bajarlo entero. */
internal fun interface RangeReader {
    /** Bytes [start, start+length) de [fileId] (menos si el fichero se acaba antes). */
    suspend fun read(fileId: String, start: Long, length: Int): ByteArray
}

internal object DriveAccess {
    /** Padres por petición: «('a' in parents or …)» con ids de 33 caracteres cabe de sobra en la URL. */
    const val PARENTS_PER_QUERY = 40

    /** Peticiones simultáneas del escáner. */
    const val PARALLEL = 4

    /** El mejor listador para [drive]: por lotes de padres con el REST de la app; si no, uno a uno en paralelo. */
    fun listerFor(drive: DriveApi): ChildLister =
        (drive as? DriveRestApi)?.let { rest -> ChildLister { parents -> rest.listChildrenOfParents(parents) } }
            ?: parallelLister(drive)

    /** Sin varios padres por petición: [DriveApi.listChildren] de cada carpeta, con [PARALLEL] a la vez como mucho. */
    fun parallelLister(drive: DriveApi, parallel: Int = PARALLEL): ChildLister {
        val gate = Semaphore(parallel)
        return ChildLister { parents ->
            coroutineScope {
                parents.map { parent ->
                    async { gate.withPermit { drive.listChildren(parent).map { ScanItem.of(it, parent) } } }
                }.awaitAll().flatten()
            }
        }
    }

    /** Lector de rangos con el REST de la app; null si [drive] es otra implementación. */
    fun rangeReaderFor(drive: DriveApi): RangeReader? =
        (drive as? DriveRestApi)?.let { rest -> RangeReader { id, start, length -> rest.readRange(id, start, length) } }
}

@Serializable
private class ScanPage(val nextPageToken: String? = null, val files: List<ScanItem> = emptyList())

private const val SCAN_FIELDS = "id,name,mimeType,size,md5Checksum,headRevisionId,modifiedTime,parents"

/** Consulta «('a' in parents or 'b' in parents …) and trashed=false» (pública para las pruebas). */
internal fun parentsQuery(parentIds: List<String>): String =
    parentIds.joinToString(" or ", prefix = "(", postfix = ")") { "'${DriveRestApi.escapeQuery(it)}' in parents" } +
        " and trashed=false"

/** Hijos de varias carpetas en una sola consulta paginada (pageSize 1000), con `headRevisionId`. */
internal suspend fun DriveRestApi.listChildrenOfParents(parentIds: List<String>): List<ScanItem> {
    require(parentIds.isNotEmpty())
    val out = ArrayList<ScanItem>()
    var pageToken: String? = null
    do {
        val token = pageToken
        val url: HttpUrl = endpoint("files") {
            addQueryParameter("q", parentsQuery(parentIds))
            addQueryParameter("fields", "nextPageToken,files($SCAN_FIELDS)")
            addQueryParameter("pageSize", "1000")
            if (token != null) addQueryParameter("pageToken", token)
        }
        val page = getJson(url, ScanPage.serializer())
        out += page.files
        pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
    } while (pageToken != null)
    return out
}

/**
 * Bytes [start, start+length) de un fichero (`Range: bytes=start-end`). Si Drive ignora el `Range` (200), se saltan
 * los primeros [start] bytes del cuerpo (solo se admite hasta [MAX_SKIP_ON_200]). Un 416 (empieza más allá del
 * final) da un array vacío.
 */
internal suspend fun DriveRestApi.readRange(fileId: String, start: Long, length: Int): ByteArray {
    require(start >= 0 && length > 0) { "rango inválido: $start+$length" }
    val url = endpoint("files", fileId) { addQueryParameter("alt", "media") }
    val headers = mapOf("Accept-Encoding" to "identity", "Range" to "bytes=$start-${start + length - 1}")
    return request(url, headers, accept = { it == 200 || it == 206 || it == 416 }) { resp ->
        when (resp.code) {
            416 -> ByteArray(0)
            else -> resp.body.byteStream().use { input ->
                if (resp.code == 206) {
                    val range = DriveRestApi.parseContentRange(resp.header("Content-Range"))
                    if (range != null && range.start != start) {
                        throw DriveHttpException(206, null, "Content-Range inesperado: ${resp.header("Content-Range")}")
                    }
                }
                if (resp.code == 200 && start > 0) {
                    if (start > MAX_SKIP_ON_200) throw DriveHttpException(200, null, "Drive ignoró el Range y el salto es demasiado grande")
                    var toSkip = start
                    val skipBuf = ByteArray(64 * 1024)
                    while (toSkip > 0) {
                        val n = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), toSkip).toInt())
                        if (n < 0) return@use ByteArray(0)
                        toSkip -= n
                    }
                }
                val buf = ByteArray(length)
                var n = 0
                while (n < length) {
                    val r = input.read(buf, n, length - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        }
    }
}

private const val MAX_SKIP_ON_200 = 16L * 1024 * 1024
