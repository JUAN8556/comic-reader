package mx.jano.comics.settings

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Escritura atómica de los ficheros de estado de la app (settings.json, index.json, progress.json…).
 *
 * - Se escribe a un temporal en la misma carpeta, se hace `fsync` y se renombra encima: quien lea nunca ve un JSON
 *   a medias, y un corte de luz deja el fichero viejo o el nuevo, nunca uno roto.
 * - Con [keepBackup], antes del último rename el fichero vigente pasa a `<nombre>.bak` (otro rename, sin copiar
 *   bytes). Si se corta justo entre los dos renames, el fichero no existe pero el `.bak` sí, y [readWithFallback]
 *   lo encuentra.
 *
 * Lo usan los paquetes settings, library y metadata (todo el módulo: `internal`).
 */
internal object AtomicFiles {

    /** El JSON común de los ficheros de estado: tolerante al leer y con los valores por defecto escritos. */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true   // un enum desconocido (versión futura) vuelve a su valor por defecto
        encodeDefaults = true
        explicitNulls = false
    }

    fun backupOf(file: File): File = File(file.path + ".bak")

    /** Escribe [bytes] en [file] de forma atómica (ver la clase). */
    fun write(file: File, bytes: ByteArray, keepBackup: Boolean = false) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("Sin carpeta para $file")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("No se pudo crear $dir")
        val tmp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            if (keepBackup && file.isFile && file.length() > 0) {
                move(file, backupOf(file))
            }
            move(tmp, file)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun writeText(file: File, text: String, keepBackup: Boolean = false) =
        write(file, text.toByteArray(Charsets.UTF_8), keepBackup)

    /**
     * Lee [file] y lo pasa por [parse]; si no existe, está vacío o [parse] falla, lo intenta con su `.bak`.
     * Devuelve null si ninguno vale. [onProblem] recibe una descripción de lo que falló (para trazas).
     */
    fun <T> readWithFallback(file: File, onProblem: (String) -> Unit = {}, parse: (String) -> T): T? {
        for (candidate in listOf(file, backupOf(file))) {
            if (!candidate.isFile || candidate.length() == 0L) continue
            try {
                return parse(candidate.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                onProblem("${candidate.name} ilegible: ${e::class.java.simpleName}: ${e.message?.take(200)}")
            }
        }
        return null
    }

    /** Borra temporales huérfanos (`.<nombre>.*.tmp`) que dejó una escritura cortada. */
    fun cleanTemporaries(file: File) {
        val dir = file.absoluteFile.parentFile ?: return
        dir.listFiles { f -> f.name.startsWith(".${file.name}.") && f.name.endsWith(".tmp") }?.forEach { it.delete() }
    }

    private fun move(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
