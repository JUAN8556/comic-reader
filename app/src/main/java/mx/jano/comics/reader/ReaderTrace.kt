package mx.jano.comics.reader

/**
 * Trazas del lector para la sonda: extracción de cada página (ms, KB), página asentada, página dibujada (ms desde
 * que el pager la compuso) y errores. El contrato de [ReaderScreen] no lleva registro, así que quien quiera las
 * trazas pone aquí su destino mientras el lector está abierto y lo quita al cerrarlo. Sin destino no cuesta nada.
 * Se llama desde cualquier hilo.
 */
object ReaderTrace {
    @Volatile
    var sink: ((String) -> Unit)? = null

    internal fun log(message: String) {
        val s = sink ?: return
        runCatching { s(message) }
    }
}
