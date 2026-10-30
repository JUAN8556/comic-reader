package mx.jano.comics.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException

/** Lo que se guarda en files/settings.json. */
@Serializable
internal data class SettingsFile(
    val version: Int = 1,
    val app: AppSettings = AppSettings(),
    val reader: ReaderSettings = ReaderSettings(),
    /** RTL explícito por ruta (libro o carpeta, normalizada con [PathKeys]); lo que no está, se hereda. */
    val rtl: Map<String, Boolean> = emptyMap(),
)

/**
 * [SettingsStore] sobre un JSON atómico ([AtomicFiles], con `.bak`).
 *
 * - Se carga al construirse, de forma síncrona: son unos pocos KB y así `app.value` es el bueno desde el primer
 *   fotograma (sin parpadeo de valores por defecto). Si el fichero y su `.bak` están rotos, valores por defecto.
 * - Cada cambio se serializa con un [Mutex], se escribe a disco en Dispatchers.IO y después se publica.
 *   Si el disco falla, el cambio se publica igualmente (vale para esta sesión) y se avisa por [log].
 * - RTL efectivo: libro > carpeta más cercana hacia arriba (hasta la raíz «») > global.
 */
class JsonSettingsStore internal constructor(
    private val file: File,
    private val log: (String) -> Unit = {},
) : SettingsStore {

    private val mutex = Mutex()
    private val initial: SettingsFile = load()

    private val appFlow = MutableStateFlow(initial.app)
    private val readerFlow = MutableStateFlow(initial.reader)
    private val rtlFlow = MutableStateFlow(initial.rtl)

    override val app: StateFlow<AppSettings> = appFlow.asStateFlow()
    override val reader: StateFlow<ReaderSettings> = readerFlow.asStateFlow()

    /**
     * Tabla de RTL explícito (ruta normalizada → valor). No está en el contrato; sirve para que la interfaz se
     * recomponga cuando cambie (las llamadas a [rtlFor] no son observables por sí solas).
     */
    val rtlTable: StateFlow<Map<String, Boolean>> = rtlFlow.asStateFlow()

    override suspend fun updateApp(transform: (AppSettings) -> AppSettings) {
        mutate { it.copy(app = transform(it.app)) }
    }

    override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) {
        mutate { it.copy(reader = transform(it.reader)) }
    }

    override fun rtlFor(bookPath: String): Boolean =
        RtlResolver.resolve(rtlFlow.value, bookPath, readerFlow.value.rtlGlobal)

    /** Valor explícito de [path] (sin heredar), o null si hereda. */
    fun rtlOverride(path: String): Boolean? = rtlFlow.value[PathKeys.normalize(path)]

    override suspend fun setRtl(path: String, rtl: Boolean?) {
        val key = PathKeys.normalize(path)
        mutate { current ->
            val table = current.rtl.toMutableMap()
            if (rtl == null) table.remove(key) else table[key] = rtl
            current.copy(rtl = table)
        }
    }

    private suspend fun mutate(change: (SettingsFile) -> SettingsFile) {
        mutex.withLock {
            val before = snapshot()
            val after = change(before)
            if (after == before) return
            withContext(Dispatchers.IO) {
                try {
                    AtomicFiles.writeText(file, AtomicFiles.json.encodeToString(SettingsFile.serializer(), after), keepBackup = true)
                } catch (e: IOException) {
                    log("ajustes: no se pudieron guardar (${e.message}); valen solo para esta sesión")
                }
            }
            appFlow.value = after.app
            readerFlow.value = after.reader
            rtlFlow.value = after.rtl
        }
    }

    internal companion object {
        @Volatile private var instance: JsonSettingsStore? = null

        fun shared(context: android.content.Context): JsonSettingsStore =
            instance ?: synchronized(this) {
                instance ?: JsonSettingsStore(
                    File(context.applicationContext.filesDir, FILE_NAME),
                    log = { android.util.Log.w("Ajustes", it) },
                ).also { instance = it }
            }

        const val FILE_NAME = "settings.json"
    }

    private fun snapshot() = SettingsFile(app = appFlow.value, reader = readerFlow.value, rtl = rtlFlow.value)

    private fun load(): SettingsFile {
        AtomicFiles.cleanTemporaries(file)
        val loaded = AtomicFiles.readWithFallback(file, onProblem = log) { text ->
            AtomicFiles.json.decodeFromString(SettingsFile.serializer(), text)
        } ?: return SettingsFile()
        // Las claves se vuelven a normalizar por si el fichero lo escribió una versión que no lo hacía.
        return loaded.copy(rtl = loaded.rtl.mapKeys { PathKeys.normalize(it.key) })
    }
}

/** Resolución pura de RTL (con pruebas JVM). */
internal object RtlResolver {
    fun resolve(table: Map<String, Boolean>, bookPath: String, global: Boolean): Boolean {
        if (table.isEmpty()) return global
        for (candidate in PathKeys.selfAndAncestors(PathKeys.normalize(bookPath))) {
            table[candidate]?.let { return it }
        }
        return global
    }
}
