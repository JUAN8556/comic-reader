package mx.jano.comics.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.drive.DriveRestApi
import mx.jano.comics.settings.AtomicFiles
import java.io.File
import java.io.IOException
import java.text.Normalizer

/**
 * Los ficheros de `Cómics/.indice/` que deja la migración. Encuentra la carpeta bajo la raíz, lista lo que hay y baja
 * a [dir] (files/indice) los ficheros pequeños que cambiaron, comparando md5 (o tamaño y fecha si Drive no da md5)
 * con lo último que se bajó ([STATE_FILE]). La comparten [DriveMetadataRepository] y el almacén de portadas.
 *
 * Si `.indice` no existe, todo devuelve vacío sin error: la app funciona sin índice.
 */
internal class IndiceSync(
    private val dir: File,
    private val drive: DriveApi,
    private val rootId: suspend () -> String?,
    private val log: (String) -> Unit = {},
) {
    @Serializable
    data class Remote(val id: String, val md5: String? = null, val size: Long? = null, val modifiedTime: String? = null) {
        fun sameAs(other: Remote?): Boolean = other != null && other.id == id &&
            if (md5 != null || other.md5 != null) md5 == other.md5 && size == other.size
            else size == other.size && modifiedTime == other.modifiedTime
    }

    @Serializable
    data class State(val folderId: String? = null, val files: Map<String, Remote> = emptyMap())

    private val mutex = Mutex()
    private val stateFile = File(dir, STATE_FILE)
    @Volatile private var cachedFolder: Pair<String, String>? = null   // rootId → id de .indice

    /** Lo que hay ahora en `.indice` (por nombre NFC), o null si no hay raíz o no existe la carpeta. */
    suspend fun listRemote(): Map<String, DriveFile>? {
        val root = rootId() ?: return null
        val folderId = indiceFolder(root) ?: return null
        return drive.listChildren(folderId)
            .filter { !it.isFolder }
            .associateBy { nfc(it.name) }
    }

    private suspend fun indiceFolder(root: String): String? {
        cachedFolder?.let { (r, id) -> if (r == root) return id }
        val found = drive.listChildren(root).firstOrNull { it.isFolder && nfc(it.name) == FOLDER_NAME }?.id
        if (found != null) cachedFolder = root to found
        return found
    }

    /**
     * Baja a [dir] los [names] que cambiaron. Devuelve los nombres bajados (vacío si nada cambió) o null si
     * `.indice` no existe. Los que ya no están en Drive se borran de [dir].
     */
    suspend fun sync(names: Set<String>): Set<String>? = mutex.withLock {
        val remote = listRemote() ?: return@withLock null
        withContext(Dispatchers.IO) {
            val state = readState()
            val nextFiles = state.files.toMutableMap()
            val changed = LinkedHashSet<String>()
            for (name in names) {
                val file = remote[name]
                val local = File(dir, name)
                if (file == null) {
                    if (nextFiles.remove(name) != null || local.exists()) {
                        local.delete()
                        changed += name
                    }
                    continue
                }
                val now = Remote(file.id, file.md5Checksum, file.size, file.modifiedTime)
                if (now.sameAs(state.files[name]) && local.isFile) continue
                download(file, local)
                nextFiles[name] = now
                changed += name
            }
            if (changed.isNotEmpty()) writeState(State(folderId = cachedFolder?.second, files = nextFiles))
            changed
        }
    }

    fun local(name: String): File? = File(dir, name).takeIf { it.isFile && it.length() > 0 }

    /**
     * Baja [file] a [dest] pasando por un temporal (quien lea [dest] nunca ve uno a medias). Con [resumable], si se
     * corta, el temporal se queda y la siguiente vez se sigue con `Range` (el cliente comprueba que sea la misma
     * versión): para portadas.zip, que son cientos de MB.
     */
    suspend fun download(file: DriveFile, dest: File, resumable: Boolean = false) {
        dest.absoluteFile.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        var done = false
        try {
            drive.download(file.id, tmp, resume = resumable)
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("No se pudo renombrar ${tmp.name}")
            }
            done = true
            log("índice: bajado ${dest.name} (${dest.length()} bytes)")
        } finally {
            if (done || !resumable) {
                tmp.delete()
                DriveRestApi.partialVersionFile(tmp).delete()
            }
        }
    }

    private fun readState(): State =
        AtomicFiles.readWithFallback(stateFile, onProblem = log) { AtomicFiles.json.decodeFromString(State.serializer(), it) } ?: State()

    private fun writeState(state: State) {
        try {
            AtomicFiles.writeText(stateFile, AtomicFiles.json.encodeToString(State.serializer(), state))
        } catch (e: IOException) {
            log("índice: no se pudo guardar $STATE_FILE: ${e.message}")
        }
    }

    companion object {
        const val FOLDER_NAME = ".indice"
        const val STATE_FILE = "estado.json"
        const val INDICE = "indice.jsonl"
        const val SERIES = "series.jsonl"
        const val TAXONOMIA = "taxonomia.json"
        const val PORTADAS = "portadas.zip"

        fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
    }
}
