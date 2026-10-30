package mx.jano.comics.cache

/** Textos de la caché y de las descargas (español). La i18n llega después: aquí quedan juntos para moverlos de una vez. */
internal object CacheStrings {
    // Canal y notificación de los libros fijados (descarga en segundo plano)
    const val CHANNEL_NAME = "Descargas sin conexión"
    const val CHANNEL_DESCRIPTION = "Progreso de los libros fijados para leer sin conexión"
    const val PIN_STARTING = "Preparando las descargas sin conexión"
    const val PIN_WAITING_NETWORK = "Esperando conexión para seguir descargando"
    const val PIN_WAITING_WIFI = "Esperando Wi-Fi para seguir descargando"
    const val PIN_PAUSED = "Descargas sin conexión en pausa"
    const val PIN_NO_SPACE = "Sin espacio: se paró la descarga de los fijados"
    const val PIN_NEEDS_APP = "Abre Cómics para seguir descargando"
    const val PIN_FAILED = "No se pudo terminar la descarga de los fijados"
    const val ACTION_PAUSE = "Pausar"
    const val ACTION_RESUME = "Reanudar"
    const val ACTION_CANCEL = "Cancelar"

    fun pinTitle(done: Int, total: Int) = "Sin conexión: $done de $total"
    fun pinDone(count: Int) = if (count == 1) "Listo: 1 libro para leer sin conexión" else "Listo: $count libros para leer sin conexión"
    fun pinCurrent(name: String, bytes: Long, total: Long, bytesPerSecond: Long?): String {
        val speed = bytesPerSecond?.takeIf { it > 0 }?.let { " · ${formatBytes(it)}/s" } ?: ""
        return "$name · ${formatBytes(bytes)} de ${formatBytes(total)}$speed"
    }
    fun noSpaceDetail(needed: Long, free: Long, minFree: Long) =
        "Hacen falta ${formatBytes(needed)} y hay ${formatBytes(free)} libres (se guardan ${formatBytes(minFree)} siempre libres)"

    // Motivos de una transferencia (TransferItem.reason → etiqueta para la interfaz)
    const val REASON_READ_LABEL = "Abriendo"
    const val REASON_PIN_LABEL = "Sin conexión"
    const val REASON_PREFETCH_LABEL = "Siguiente número"

    // Errores
    const val ERROR_NO_SPACE = "No hay espacio libre suficiente en la tablet"
    const val ERROR_CORRUPT = "La descarga llegó dañada (no coincide con Drive)"
    const val ERROR_CANCELLED = "Descarga cancelada"
    const val ERROR_NOT_IN_DRIVE = "El archivo ya no está en Drive"

    /** «1,3 GB», «820 MB», «95 KB»: unidades decimales, como las muestra Android en Almacenamiento. */
    fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        return when {
            b >= 1_000_000_000L -> oneDecimal(b / 1_000_000_000.0) + " GB"
            b >= 1_000_000L -> "${b / 1_000_000L} MB"
            b >= 1_000L -> "${b / 1_000L} KB"
            else -> "$b B"
        }
    }

    private fun oneDecimal(value: Double): String {
        val tenths = Math.round(value * 10)
        return "${tenths / 10},${tenths % 10}"
    }
}
