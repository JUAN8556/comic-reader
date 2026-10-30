package mx.jano.comics.probe

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.Writer
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Registro de la sonda: líneas con hora en memoria (para la pantalla) y añadidas a [file] (para `adb pull`).
 * Se puede llamar desde cualquier hilo; el fichero lo escribe una sola corrutina en Dispatchers.IO, con flush por línea.
 * NUNCA se registran tokens: quien llama solo pasa longitudes y tiempos.
 */
class SondaLog(val file: File?, scope: CoroutineScope) {
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private val pending = Channel<String>(Channel.UNLIMITED)
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    init {
        scope.launch(Dispatchers.IO) {
            var writer: Writer? = null
            try {
                writer = runCatching {
                    file?.parentFile?.mkdirs()
                    file?.let { java.io.FileOutputStream(it, true).bufferedWriter() }
                }.getOrNull()
                for (line in pending) {
                    val w = writer ?: continue
                    runCatching {
                        w.write(line)
                        w.write("\n")
                        w.flush()
                    }
                }
            } finally {
                runCatching { writer?.close() }
            }
        }
    }

    fun line(message: String) {
        val stamped = "${LocalTime.now().format(clock)}  $message"
        _lines.update { (it + stamped).takeLast(MAX_LINES) }
        pending.trySend(stamped)
    }

    /** Línea de cabecera solo para el fichero y la pantalla, sin hora delante. */
    fun header(message: String) {
        _lines.update { (it + message).takeLast(MAX_LINES) }
        pending.trySend(message)
    }

    fun error(what: String, t: Throwable) = line("$what: ERROR ${describe(t)}")

    fun text(): String = _lines.value.joinToString("\n")

    companion object {
        private const val MAX_LINES = 2000

        /** Clase + mensaje, y la primera causa si la hay. */
        fun describe(t: Throwable): String {
            val base = "${t::class.java.name}: ${t.message ?: "(sin mensaje)"}"
            val cause = t.cause?.takeIf { it !== t } ?: return base
            return "$base (causa: ${cause::class.java.name}: ${cause.message ?: "(sin mensaje)"})"
        }
    }
}
