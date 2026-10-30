package mx.jano.comics.drive

import kotlinx.serialization.Serializable
import mx.jano.comics.auth.TokenProvider
import java.io.File

/** CONTRATO COMÚN. Cliente mínimo de la API REST v3 de Drive: 4 llamadas, sin google-api-client. */
@Serializable
data class DriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long? = null,
    val md5Checksum: String? = null,
    val modifiedTime: String? = null,
    val parents: List<String>? = null,
) {
    val isFolder: Boolean get() = mimeType == "application/vnd.google-apps.folder"
}

@Serializable
data class DriveAbout(val userEmail: String? = null, val limitBytes: Long? = null, val usageBytes: Long? = null)

class DriveHttpException(val code: Int, val reason: String?, message: String) : Exception(message)

interface DriveApi {
    /** Busca una carpeta por nombre exacto bajo [parentId] («root» = Mi unidad). */
    suspend fun findFolder(name: String, parentId: String = "root"): DriveFile?

    /** Hijos directos de una carpeta, paginando internamente, ordenados por nombre; sin los de la papelera. */
    suspend fun listChildren(folderId: String): List<DriveFile>

    /**
     * Descarga `files.get?alt=media` a [dest]. Si [dest] ya tiene bytes y [resume] es true, pide `Range: bytes=<len>-`
     * y añade al final. Renueva el token si recibe 401 (una vez). Reintenta 403 rateLimit/429 con backoff.
     */
    suspend fun download(fileId: String, dest: File, resume: Boolean = true, onProgress: (bytes: Long, total: Long?) -> Unit = { _, _ -> })

    suspend fun about(): DriveAbout
}

object DriveApis {
    /** Implementación con OkHttp + kotlinx.serialization. La rellena el frente «auth». */
    fun rest(tokens: TokenProvider): DriveApi = TODO("sonda/drive")
}
