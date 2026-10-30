package mx.jano.comics.cache

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Reloj inyectable: las pruebas de la política LRU usan uno falso. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/** Espacio libre del volumen donde vive la caché. En Android, StatFs; en las pruebas, uno falso. */
fun interface StorageStats {
    fun freeBytes(dir: File): Long
}

/** Un estado que se guarda entero y se lee entero (cache.json, pins.json). */
interface JsonStateStore<T> {
    /** null si no existe o no se puede leer (roto): quien llama empieza de cero y concilia con el disco. */
    fun load(): T?
    fun save(value: T)
}

/**
 * JSON guardado de forma atómica: se escribe `<nombre>.tmp` en la misma carpeta, se fuerza a disco (fsync) y se
 * renombra encima del bueno. Un corte a mitad deja el fichero anterior intacto, nunca uno a medias.
 */
class AtomicJsonFile<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
) : JsonStateStore<T> {
    private val lock = Any()

    override fun load(): T? = synchronized(lock) {
        if (!file.isFile) return null
        try {
            json.decodeFromString(serializer, file.readText())
        } catch (_: Exception) {
            null
        }
    }

    override fun save(value: T) = synchronized(lock) {
        file.absoluteFile.parentFile?.mkdirs()
        val tmp = File(file.path + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        moveReplacing(tmp, file)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** Renombra [from] encima de [to]; atómico si el sistema de ficheros lo permite (en Android, sí). */
internal fun moveReplacing(from: File, to: File) {
    try {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

internal const val TMP_SUFFIX = ".tmp"

/** Para qué se baja un libro: decide cuánto se puede apretar el disco (ver [EvictionPolicy]). */
enum class DownloadPurpose {
    /** El libro que se va a abrir: puede pasar del tope si la tablet tiene sitio de verdad. */
    READ,

    /** Un libro fijado: puede pasar del tope (lo pidió el usuario), nunca del margen libre. */
    PIN,

    /** El N+1: respeta tope y margen; si no cabe, no se baja. */
    PREFETCH,
}

/** Motivos de [TransferItem.reason]. La interfaz los traduce con [reasonLabel]. */
object TransferReasons {
    const val READ = "read"
    const val PIN = "pin"
    const val PREFETCH = "prefetch"

    fun reasonLabel(reason: String): String = when (reason) {
        PIN -> CacheStrings.REASON_PIN_LABEL
        PREFETCH -> CacheStrings.REASON_PREFETCH_LABEL
        else -> CacheStrings.REASON_READ_LABEL
    }
}

/** No cabe el libro sin romper el margen libre (o, para el N+1, el tope). */
class InsufficientSpaceException(
    val bookId: String,
    val purpose: DownloadPurpose,
    val neededBytes: Long,
    val freeBytes: Long,
    val minFreeBytes: Long,
) : IOException("${CacheStrings.ERROR_NO_SPACE}: ${CacheStrings.noSpaceDetail(neededBytes, freeBytes, minFreeBytes)}")

/** Lo bajado no coincide con Drive (tamaño o md5), ni a la segunda. */
class CorruptDownloadException(val bookId: String, val why: String) : IOException("${CacheStrings.ERROR_CORRUPT}: $why")

/** Alguien canceló la descarga (cancelDownload, evict, clearAll) mientras otro la esperaba. */
class DownloadCancelledException(val bookId: String) : IOException(CacheStrings.ERROR_CANCELLED)

/**
 * Velocidad de una descarga con una ventana deslizante: bytes/s de los últimos [windowMs] ms. Recibe los bytes
 * acumulados en disco (no los del bloque), así una reanudación no cuenta lo que ya estaba.
 */
class SpeedMeter(private val clock: Clock, private val windowMs: Long = 3_000) {
    private val times = ArrayDeque<Long>()
    private val totals = ArrayDeque<Long>()

    /** Registra que hay [bytesOnDisk] y devuelve la velocidad actual (0 hasta tener dos muestras separadas). */
    fun add(bytesOnDisk: Long): Long {
        val now = clock.now()
        times.addLast(now)
        totals.addLast(bytesOnDisk)
        while (times.size > 2 && now - times.first() > windowMs) {
            times.removeFirst()
            totals.removeFirst()
        }
        return current()
    }

    fun current(): Long {
        if (times.size < 2) return 0
        val dt = times.last() - times.first()
        val db = totals.last() - totals.first()
        return if (dt <= 0 || db <= 0) 0 else db * 1000 / dt
    }
}
