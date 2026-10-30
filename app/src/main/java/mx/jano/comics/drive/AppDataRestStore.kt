package mx.jano.comics.drive

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * El appDataFolder con el mismo cliente (token, reintentos, trazas) que [drive], si es el REST de la app
 * ([DriveRestApi]); null si es otra implementación (una de pruebas, un envoltorio…).
 */
fun AppDataStores.from(drive: DriveApi): AppDataStore? =
    (drive as? DriveRestApi)?.let { AppDataRestStore(it, log = SondaLog::i) }

/**
 * [AppDataStore] sobre la API REST v3 de Drive, reutilizando la petición de [DriveRestApi] (token, 401 → token
 * nuevo y un reintento, 403/429 de cuota → backoff, errores con el `reason` de Drive).
 *
 * - list: `GET files?spaces=appDataFolder&fields=nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)`.
 *   (`mimeType` va en los campos porque [DriveFile] lo exige.)
 * - put: si ya hay un fichero con ese nombre, `PATCH upload/drive/v3/files/{id}?uploadType=media` con los bytes;
 *   si no, `POST upload/drive/v3/files?uploadType=multipart` (multipart/related: metadatos con
 *   `parents=["appDataFolder"]` + contenido). Si el PATCH da 404 (lo borraron), se crea.
 * - get: `GET files/{id}?alt=media`; null si no existe.
 *
 * Los id por nombre se recuerdan en memoria para no listar en cada `put`. Si hay dos ficheros con el mismo nombre
 * (dos subidas a la vez desde dos dispositivos), vale el más reciente.
 */
internal class AppDataRestStore(
    private val api: DriveRestApi,
    private val log: (String) -> Unit = {},
) : AppDataStore {

    private val ids = HashMap<String, String>()
    private val mutex = Mutex()

    /** `https://www.googleapis.com/drive/v3/` → `https://www.googleapis.com/upload/drive/v3/`. */
    private val uploadBase: HttpUrl = api.baseUrl.newBuilder().encodedPath("/upload" + api.baseUrl.encodedPath).build()

    override suspend fun list(): List<DriveFile> {
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        do {
            val token = pageToken
            val url = api.endpoint("files") {
                addQueryParameter("spaces", SPACE)
                addQueryParameter("fields", "nextPageToken,files($FIELDS)")
                addQueryParameter("pageSize", "1000")
                if (token != null) addQueryParameter("pageToken", token)
            }
            val page = api.getJson(url, AppDataList.serializer())
            out += page.files
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        return out
    }

    override suspend fun put(name: String, bytes: ByteArray, mimeType: String): DriveFile = mutex.withLock {
        val existing = idFor(name)
        if (existing != null) {
            val updated = try {
                update(existing, bytes, mimeType)
            } catch (e: DriveHttpException) {
                if (e.code != 404) throw e
                log("appData: «$name» ya no existe en Drive (404); lo creo de nuevo")
                ids.remove(name)
                null
            }
            if (updated != null) return@withLock updated
        }
        create(name, bytes, mimeType).also { ids[name] = it.id }
    }

    override suspend fun get(name: String): ByteArray? {
        val id = mutex.withLock { idFor(name) } ?: return null
        val url = api.endpoint("files", id) { addQueryParameter("alt", "media") }
        return try {
            api.request(url, mapOf("Accept-Encoding" to "identity")) { resp -> resp.body.bytes() }
        } catch (e: DriveHttpException) {
            if (e.code != 404) throw e
            mutex.withLock { ids.remove(name) }
            null
        }
    }

    /** Id del fichero [name] (de memoria o listando por nombre); null si no existe. Llamar con [mutex]. */
    private suspend fun idFor(name: String): String? {
        ids[name]?.let { return it }
        val url = api.endpoint("files") {
            addQueryParameter("spaces", SPACE)
            addQueryParameter("q", "name = '${DriveRestApi.escapeQuery(name)}' and trashed=false")
            addQueryParameter("fields", "files($FIELDS)")
            addQueryParameter("orderBy", "modifiedTime desc")
            addQueryParameter("pageSize", "10")
        }
        val found = api.getJson(url, AppDataList.serializer()).files.filter { it.name == name }
        if (found.size > 1) log("appData: ${found.size} ficheros «$name»; uso el más reciente")
        val newest = found.maxByOrNull { it.modifiedTime ?: "" } ?: return null
        ids[name] = newest.id
        return newest.id
    }

    private suspend fun update(id: String, bytes: ByteArray, mimeType: String): DriveFile {
        val url = uploadBase.newBuilder().addPathSegment("files").addPathSegment(id)
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FIELDS)
            .build()
        val body = bytes.toRequestBody(mimeType.toMediaType())
        return api.request(url, method = "PATCH", body = body) { resp -> json.decodeFromString(DriveFile.serializer(), resp.body.string()) }
    }

    private suspend fun create(name: String, bytes: ByteArray, mimeType: String): DriveFile {
        val url = uploadBase.newBuilder().addPathSegment("files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FIELDS)
            .build()
        val metadata = buildJsonObject {
            put("name", name)
            put("mimeType", mimeType)
            putJsonArray("parents") { add(SPACE) }
        }.toString()
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        return api.request(url, method = "POST", body = body) { resp -> json.decodeFromString(DriveFile.serializer(), resp.body.string()) }
    }

    @Serializable
    private class AppDataList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

    companion object {
        const val SPACE = "appDataFolder"
        const val FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime"
        private val json = Json { ignoreUnknownKeys = true }
    }
}
