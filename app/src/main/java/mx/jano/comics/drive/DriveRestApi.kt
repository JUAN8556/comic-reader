package mx.jano.comics.drive

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mx.jano.comics.auth.AccountAware
import mx.jano.comics.auth.TokenProvider
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * [DriveApi] sobre la API REST v3 de Drive con OkHttp y kotlinx.serialization (sin google-api-client).
 *
 * Política de errores (igual para las cuatro llamadas):
 * - 401 → `tokens.invalidate(token)` y UN reintento con el token nuevo.
 * - 403 rateLimitExceeded / userRateLimitExceeded y 429 → backoff exponencial con jitter (1 s, 2 s, 4 s, 8 s
 *   + 0-1 s; tope 64 s; respeta Retry-After si es mayor), máximo [MAX_RATE_LIMIT_ATTEMPTS] intentos en total.
 * - 500/502/503/504 (el «backendError» pasajero de Drive) → el mismo backoff y el mismo tope, salvo en un POST (crear
 *   un fichero del appDataFolder dos veces lo duplicaría si el primero sí llegó). Un solo 5xx ya no tumba un
 *   escaneo de ~150 peticiones.
 * - 403 cannotDownloadAbusiveFile en una descarga → UN reintento con `acknowledgeAbuse=true` (Jano es el dueño).
 * - Lo demás → [DriveHttpException] con el `error.errors[0].reason` del JSON.
 *
 * Reanudación: antes de cada descarga se pide la versión del fichero (`headRevisionId`, `md5Checksum`, `size`) y se
 * guarda junto al destino ([partialVersionFile], `<dest>.meta`). Un parcial solo se reanuda con `Range` si su huella
 * coincide con la versión actual de Drive; si falta o cambió, se borra y se baja desde cero (si no, se pegarían
 * bytes de la versión nueva detrás de los de la vieja).
 *
 * Nunca registra la cabecera Authorization ni el token: el [log] solo recibe rutas, códigos, tamaños y tiempos.
 *
 * @param sleeper espera del backoff; los tests la sustituyen para no esperar de verdad.
 * @param log destino de las trazas de la sonda (en la app, Log.i con tag "Sonda").
 */
class DriveRestApi(
    private val tokens: TokenProvider,
    private val client: OkHttpClient = defaultClient,
    internal val baseUrl: HttpUrl = DEFAULT_BASE_URL.toHttpUrl(),
    private val sleeper: suspend (millis: Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) : DriveApi {

    override suspend fun findFolder(name: String, parentId: String): DriveFile? {
        val q = "name = '${escapeQuery(name)}' and mimeType = '$FOLDER_MIME' and " +
            "'${escapeQuery(parentId)}' in parents and trashed=false"
        val url = endpoint("files") {
            addQueryParameter("q", q)
            addQueryParameter("fields", "files($FILE_FIELDS)")
            addQueryParameter("orderBy", "createdTime")
            addQueryParameter("pageSize", "10")
        }
        val found = getJson(url, FileList.serializer()).files
        if (found.size > 1) log("findFolder: ${found.size} carpetas con ese nombre; uso la más antigua")
        return found.firstOrNull()
    }

    override suspend fun listChildren(folderId: String): List<DriveFile> {
        val t0 = System.nanoTime()
        val out = ArrayList<DriveFile>()
        var pageToken: String? = null
        var pages = 0
        do {
            val token = pageToken
            val url = endpoint("files") {
                addQueryParameter("q", "'${escapeQuery(folderId)}' in parents and trashed=false")
                addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                addQueryParameter("pageSize", "1000")
                addQueryParameter("orderBy", "folder,name_natural")
                if (token != null) addQueryParameter("pageToken", token)
            }
            val page = getJson(url, FileList.serializer())
            out += page.files
            pages++
            pageToken = page.nextPageToken?.takeIf { it.isNotEmpty() }
        } while (pageToken != null)
        log("listChildren: ${out.size} elementos en $pages páginas, ${elapsedMs(t0)} ms")
        return out
    }

    override suspend fun about(): DriveAbout {
        val url = endpoint("about") { addQueryParameter("fields", "user(emailAddress),storageQuota(limit,usage)") }
        val wire = getJson(url, AboutWire.serializer())
        val about = DriveAbout(
            userEmail = wire.user?.emailAddress,
            limitBytes = wire.storageQuota?.limit,
            usageBytes = wire.storageQuota?.usage,
        )
        // Red de seguridad: si Play Services no dijo qué cuenta se eligió, se fija con el correo que ve Drive.
        val email = about.userEmail
        val memory = tokens as? AccountAware
        if (email != null && memory != null && memory.accountName == null) memory.rememberAccount(email)
        return about
    }

    override suspend fun download(
        fileId: String,
        dest: File,
        resume: Boolean,
        onProgress: (bytes: Long, total: Long?) -> Unit,
    ) {
        // La versión se pide siempre: valida el parcial y deja la huella para reanudar la próxima vez.
        val remote = fetchVersion(fileId)
        withContext(Dispatchers.IO) {
            dest.absoluteFile.parentFile?.mkdirs()
            val stamp = partialVersionFile(dest)
            if (resume && dest.isFile && dest.length() > 0) {
                val local = dest.length()
                val stale = staleReason(readPartialVersion(stamp), remote, local)
                if (stale != null) {
                    log("download: descarto el parcial de $local bytes ($stale); empiezo de cero")
                    if (!dest.delete() && dest.exists()) throw IOException("$TEXT_CANNOT_DELETE: $dest")
                } else {
                    log("download: el parcial de $local bytes es de la versión actual (${remote.describe()}); se reanuda")
                }
            }
            writePartialVersion(stamp, remote)
        }
        downloadMedia(fileId, dest, resume, onProgress)
    }

    /**
     * Primeros [bytes] bytes de un fichero (`Range: bytes=0-<bytes-1>`), para saber su formato sin bajarlo entero.
     * Si el servidor ignora el `Range` (200), se leen los [bytes] primeros y se corta la conexión.
     */
    suspend fun head(fileId: String, bytes: Int = HEAD_BYTES): ByteArray {
        require(bytes > 0) { "bytes debe ser positivo: $bytes" }
        val url = endpoint("files", fileId) { addQueryParameter("alt", "media") }
        val headers = mapOf("Accept-Encoding" to "identity", "Range" to "bytes=0-${bytes - 1}")
        return request(url, headers, accept = { it == 200 || it == 206 }) { resp ->
            resp.body.byteStream().use { input ->
                val buf = ByteArray(bytes)
                var n = 0
                while (n < bytes) {
                    val r = input.read(buf, n, bytes - n)
                    if (r < 0) break
                    n += r
                }
                buf.copyOf(n)
            }
        }
    }

    /** Versión actual del fichero en Drive (lo que identifica su contenido). */
    private suspend fun fetchVersion(fileId: String): PartialVersion {
        val url = endpoint("files", fileId) { addQueryParameter("fields", VERSION_FIELDS) }
        val wire = getJson(url, VersionWire.serializer())
        return PartialVersion(
            fileId = fileId,
            headRevisionId = wire.headRevisionId,
            md5Checksum = wire.md5Checksum,
            size = wire.size,
            modifiedTime = wire.modifiedTime,
        )
    }

    /** Descarga `alt=media` a [dest]; si [resume] y hay bytes, pide `Range`. La versión ya se comprobó en [download]. */
    private suspend fun downloadMedia(
        fileId: String,
        dest: File,
        resume: Boolean,
        onProgress: (bytes: Long, total: Long?) -> Unit,
    ) {
        val offset = if (resume && dest.isFile) dest.length() else 0L
        val url = endpoint("files", fileId) { addQueryParameter("alt", "media") }
        // identity: sin gzip, Content-Length y offsets de Range siempre en bytes del fichero real.
        val headers = buildMap {
            put("Accept-Encoding", "identity")
            if (offset > 0) put("Range", "bytes=$offset-")
        }
        val t0 = System.nanoTime()
        val outcome = request(url, headers, accept = { it == 200 || it == 206 || (it == 416 && offset > 0) }) { resp ->
            when (resp.code) {
                416 -> {
                    // «bytes */N»: si N coincide con lo que hay en disco, ya está completo.
                    val remoteSize = parseUnsatisfiedTotal(resp.header("Content-Range"))
                    if (remoteSize == null || remoteSize == offset) {
                        onProgress(offset, offset)
                        DownloadOutcome.Done(bytes = 0, from = offset)
                    } else {
                        DownloadOutcome.Restart("416: remoto $remoteSize bytes, local $offset")
                    }
                }
                206 -> {
                    val range = parseContentRange(resp.header("Content-Range"))
                    if (range == null || range.start != offset) {
                        DownloadOutcome.Restart("206 con Content-Range inesperado: ${resp.header("Content-Range")}")
                    } else {
                        val total = range.total ?: resp.body.contentLength().takeIf { it >= 0 }?.let { offset + it }
                        DownloadOutcome.Done(writeBody(resp, dest, append = true, startAt = offset, total, onProgress), from = offset)
                    }
                }
                else -> {
                    if (offset > 0) log("download: el servidor ignoró Range (200); reescribo desde cero")
                    val total = resp.body.contentLength().takeIf { it >= 0 }
                    DownloadOutcome.Done(writeBody(resp, dest, append = false, startAt = 0, total, onProgress), from = 0)
                }
            }
        }
        when (outcome) {
            is DownloadOutcome.Done -> {
                val ms = elapsedMs(t0)
                val kbps = if (ms > 0) outcome.bytes * 1000 / 1024 / ms else 0
                log("download: ${outcome.bytes} bytes nuevos (desde ${outcome.from}), ${dest.length()} en disco, $ms ms, $kbps KB/s")
            }
            is DownloadOutcome.Restart -> {
                if (!resume) throw DriveHttpException(206, null, "$TEXT_BAD_RANGE: ${outcome.why}")
                log("download: ${outcome.why}; empiezo de cero")
                downloadMedia(fileId, dest, resume = false, onProgress)
            }
        }
    }

    /** Copia el cuerpo a disco por bloques. Si la corrutina se cancela, cierra el fichero con lo ya escrito. */
    private suspend fun writeBody(
        resp: Response,
        dest: File,
        append: Boolean,
        startAt: Long,
        total: Long?,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        var onDisk = startAt
        onProgress(onDisk, total)
        val buffer = ByteArray(BUFFER_BYTES)
        resp.body.byteStream().use { input ->
            FileOutputStream(dest, append).buffered(BUFFER_BYTES).use { out ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    onDisk += n
                    onProgress(onDisk, total)
                }
            }
        }
        if (total != null && onDisk != total) {
            throw IOException("$TEXT_INCOMPLETE: $onDisk de $total bytes")
        }
        return onDisk - startAt
    }

    internal suspend fun <T> getJson(url: HttpUrl, deserializer: DeserializationStrategy<T>): T =
        request(url) { resp -> json.decodeFromString(deserializer, resp.body.string()) }

    /**
     * Ejecuta una petición con token y aplica la política de reintentos. [onSuccess] recibe la respuesta abierta si
     * [accept] acepta el código; se ejecuta en Dispatchers.IO. Por defecto GET; [method] y [body] valen para las
     * subidas del appDataFolder ([AppDataRestStore]): el cuerpo tiene que poder reenviarse (bytes en memoria),
     * porque un 401 o una cuota agotada repiten la petición entera.
     */
    internal suspend fun <T> request(
        initialUrl: HttpUrl,
        headers: Map<String, String> = emptyMap(),
        accept: (Int) -> Boolean = { it in 200..299 },
        method: String = "GET",
        body: RequestBody? = null,
        onSuccess: suspend (Response) -> T,
    ): T {
        var url = initialUrl
        var authRetried = false
        var abuseRetried = false
        var retryAttempts = 0
        while (true) {
            val token = tokens.accessToken()
            val builder = Request.Builder().url(url).method(method, body)
            headers.forEach { (k, v) -> builder.header(k, v) }
            builder.header("Authorization", "Bearer $token")
            val t0 = System.nanoTime()
            when (val outcome = execute(builder.build(), accept, onSuccess)) {
                is Outcome.Ok -> return outcome.value
                is Outcome.Failed -> {
                    log("${url.encodedPath} → HTTP ${outcome.code} ${outcome.reason ?: ""} en ${elapsedMs(t0)} ms")
                    when {
                        outcome.code == 401 && !authRetried -> {
                            authRetried = true
                            tokens.invalidate(token)
                        }
                        outcome.isRateLimit || (outcome.isTransientServerError && method != "POST") -> {
                            retryAttempts++
                            if (retryAttempts >= MAX_RATE_LIMIT_ATTEMPTS) throw outcome.toException()
                            val wait = backoffMillis(retryAttempts, outcome.retryAfterSeconds)
                            log("backoff ${wait} ms (intento $retryAttempts de $MAX_RATE_LIMIT_ATTEMPTS)")
                            sleeper(wait)
                        }
                        outcome.code == 403 && outcome.reason == REASON_ABUSIVE && !abuseRetried &&
                            url.queryParameter("alt") == "media" -> {
                            abuseRetried = true
                            url = url.newBuilder().setQueryParameter("acknowledgeAbuse", "true").build()
                        }
                        else -> throw outcome.toException()
                    }
                }
            }
        }
    }

    /**
     * Ejecuta la llamada en IO. Si la corrutina se cancela, un vigilante cancela la llamada de OkHttp para
     * desbloquear la lectura del socket al momento (sin esperar al timeout de lectura).
     */
    private suspend fun <T> execute(
        request: Request,
        accept: (Int) -> Boolean,
        onSuccess: suspend (Response) -> T,
    ): Outcome<T> = coroutineScope {
        val call = client.newCall(request)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            withContext(Dispatchers.IO) {
                try {
                    call.execute().use { resp ->
                        if (accept(resp.code)) {
                            Outcome.Ok(onSuccess(resp))
                        } else {
                            val error = parseError(resp)
                            Outcome.Failed(resp.code, error?.reason, error?.message, resp.header("Retry-After")?.trim()?.toLongOrNull())
                        }
                    }
                } catch (e: IOException) {
                    // Al cancelar, call.cancel() cierra el socket y la lectura lanza IOException («Socket closed»).
                    // Se convierte en CancellationException: si no, la cancelación llegaría al padre como un fallo.
                    currentCoroutineContext().ensureActive()
                    throw e
                }
            }
        } finally {
            watcher.cancel()
        }
    }

    private fun parseError(resp: Response): ErrorDetail? {
        val text = try {
            resp.peekBody(MAX_ERROR_BODY).string()
        } catch (_: IOException) {
            return null
        }
        val error = try {
            json.decodeFromString(ErrorEnvelope.serializer(), text).error
        } catch (_: Exception) {
            null
        } ?: return null
        return ErrorDetail(reason = error.errors.firstOrNull()?.reason, message = error.message)
    }

    private fun backoffMillis(attempt: Int, retryAfterSeconds: Long?): Long {
        val exponential = (1_000L shl (attempt - 1).coerceIn(0, 6)) + random.nextLong(0, 1_000)
        val serverHint = (retryAfterSeconds ?: 0L) * 1_000
        return maxOf(exponential, serverHint).coerceIn(1_000, MAX_BACKOFF_MS)
    }

    internal fun endpoint(vararg segments: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        baseUrl.newBuilder()
            .apply { segments.forEach { addPathSegment(it) } }
            .apply(query)
            .build()

    private sealed interface Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>
        class Failed(val code: Int, val reason: String?, val message: String?, val retryAfterSeconds: Long?) : Outcome<Nothing> {
            val isRateLimit: Boolean
                get() = code == 429 || (code == 403 && (reason == "rateLimitExceeded" || reason == "userRateLimitExceeded"))

            /** Error pasajero del servidor de Drive (backendError, mala pasarela, no disponible, tiempo agotado). */
            val isTransientServerError: Boolean
                get() = code == 500 || code == 502 || code == 503 || code == 504

            fun toException() = DriveHttpException(
                code,
                reason,
                "Drive respondió HTTP $code" + (reason?.let { " ($it)" } ?: "") + (message?.let { ": $it" } ?: ""),
            )
        }
    }

    private sealed interface DownloadOutcome {
        class Done(val bytes: Long, val from: Long) : DownloadOutcome
        class Restart(val why: String) : DownloadOutcome
    }

    private class ErrorDetail(val reason: String?, val message: String?)

    companion object {
        const val DEFAULT_BASE_URL = "https://www.googleapis.com/drive/v3/"
        const val MAX_RATE_LIMIT_ATTEMPTS = 5
        const val MAX_BACKOFF_MS = 64_000L
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val FILE_FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime,parents"
        internal const val VERSION_FIELDS = "id,size,md5Checksum,headRevisionId,modifiedTime"

        /** Lo que lee [head] por defecto: lo que necesita el sniffer de formatos (`archive/ArchiveSniff.kt`). */
        const val HEAD_BYTES = 1024
        private const val REASON_ABUSIVE = "cannotDownloadAbusiveFile"
        private const val BUFFER_BYTES = 64 * 1024
        private const val MAX_ERROR_BODY = 64L * 1024

        private const val TEXT_BAD_RANGE = "Respuesta de rango inválida"
        private const val TEXT_INCOMPLETE = "Descarga incompleta"
        private const val TEXT_CANNOT_DELETE = "No se pudo borrar el parcial de otra versión"

        private val json = Json { ignoreUnknownKeys = true }

        /** Cliente compartido: connect 15 s, lectura 60 s. Sin interceptor de logging (llevaría el token). */
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }

        /** Escapa un literal para la sintaxis `q` de Drive: primero `\`, luego `'`. */
        fun escapeQuery(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
        private val UNSATISFIED_RANGE = Regex("""bytes\s+\*/(\d+)""")

        internal class ContentRange(val start: Long, val end: Long, val total: Long?)

        internal fun parseContentRange(header: String?): ContentRange? {
            val m = header?.let { CONTENT_RANGE.find(it) } ?: return null
            val (start, end, total) = m.destructured
            return ContentRange(start.toLong(), end.toLong(), total.toLongOrNull())
        }

        internal fun parseUnsatisfiedTotal(header: String?): Long? =
            header?.let { UNSATISFIED_RANGE.find(it) }?.groupValues?.get(1)?.toLongOrNull()

        private fun elapsedMs(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

        /** Huella de la versión de la que salió el parcial [dest]: `<dest>.meta`. Quien borra [dest] puede borrarla. */
        fun partialVersionFile(dest: File): File = File(dest.path + ".meta")

        internal fun readPartialVersion(stamp: File): PartialVersion? = try {
            if (stamp.isFile) json.decodeFromString(PartialVersion.serializer(), stamp.readText()) else null
        } catch (_: Exception) {
            null // huella rota = sin huella: se vuelve a bajar
        }

        internal fun writePartialVersion(stamp: File, version: PartialVersion) {
            stamp.writeText(json.encodeToString(PartialVersion.serializer(), version))
        }

        /**
         * Por qué no se puede reanudar un parcial de [localBytes] bytes cuya huella es [saved] contra la versión
         * [remote] de Drive; null si se puede. Se compara lo que cambia con el contenido (revisión, md5, tamaño),
         * no `modifiedTime`, que también se mueve con cambios de metadatos.
         */
        internal fun staleReason(saved: PartialVersion?, remote: PartialVersion, localBytes: Long): String? = when {
            saved == null -> "no hay huella de su versión"
            saved.fileId != remote.fileId -> "la huella es de otro fichero"
            remote.headRevisionId == null && remote.md5Checksum == null -> "Drive no da revisión ni md5: no se puede comprobar"
            saved.headRevisionId != remote.headRevisionId -> "la revisión cambió en Drive"
            saved.md5Checksum != remote.md5Checksum -> "el md5 cambió en Drive"
            saved.size != remote.size -> "el tamaño cambió en Drive (${saved.size} → ${remote.size})"
            remote.size != null && localBytes > remote.size -> "en disco hay más bytes ($localBytes) que en Drive (${remote.size})"
            else -> null
        }
    }
}

/** Huella de la versión de un fichero de Drive, guardada junto a su descarga parcial. */
@Serializable
internal data class PartialVersion(
    val fileId: String,
    val headRevisionId: String? = null,
    val md5Checksum: String? = null,
    val size: Long? = null,
    val modifiedTime: String? = null,
) {
    fun describe(): String =
        "rev ${headRevisionId?.take(12) ?: "?"}, md5 ${md5Checksum?.take(8) ?: "?"}, ${size ?: "?"} bytes"
}

// --- Formas JSON de la API (solo los campos que se piden en `fields`) ---

@Serializable
private class FileList(val nextPageToken: String? = null, val files: List<DriveFile> = emptyList())

@Serializable
private class VersionWire(
    val id: String? = null,
    val size: Long? = null,
    val md5Checksum: String? = null,
    val headRevisionId: String? = null,
    val modifiedTime: String? = null,
)

@Serializable
private class AboutWire(val user: UserWire? = null, val storageQuota: QuotaWire? = null)

@Serializable
private class UserWire(val emailAddress: String? = null)

/** Drive manda los int64 como cadenas ("16106127360"); kotlinx.serialization las acepta para Long. */
@Serializable
private class QuotaWire(val limit: Long? = null, val usage: Long? = null)

@Serializable
private class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
private class ErrorBody(val code: Int? = null, val message: String? = null, val errors: List<ErrorItem> = emptyList())

@Serializable
private class ErrorItem(val reason: String? = null, val message: String? = null, val domain: String? = null)
