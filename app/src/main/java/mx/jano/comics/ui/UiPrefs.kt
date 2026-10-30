package mx.jano.comics.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mx.jano.comics.ui.library.LibraryFilter
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Preferencias propias de la interfaz que no están en AppSettings (contrato): files/ui-prefs.json. */
@Serializable
data class UiPrefs(
    /** Valores de las claves NXTPAPER guardados con «Este es el modo Ink Paper» (id «tabla/clave» → valor). */
    val inkPaperValues: Map<String, String?>? = null,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    /** Última copia del progreso en Drive hecha desde esta tablet (epoch ms). */
    val lastBackupAt: Long? = null,
)

/**
 * Store JSON atómico: escribe en un temporal, lo sincroniza a disco y lo renombra encima del bueno (un corte a medias
 * deja el fichero anterior intacto). Si el fichero falta o está roto, valores por defecto. [file] null = solo memoria.
 */
class UiPrefsStore(private val file: File?) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<UiPrefs> = _state.asStateFlow()

    suspend fun update(transform: (UiPrefs) -> UiPrefs) {
        mutex.withLock {
            val next = transform(_state.value)
            if (next == _state.value) return
            withContext(Dispatchers.IO) { write(next) }
            _state.value = next
        }
    }

    private fun load(): UiPrefs {
        val f = file ?: return UiPrefs()
        return try {
            if (f.isFile) json.decodeFromString(UiPrefs.serializer(), f.readText()) else UiPrefs()
        } catch (e: Exception) {
            UiPrefs()
        }
    }

    private fun write(prefs: UiPrefs) {
        val f = file ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(UiPrefs.serializer(), prefs).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
