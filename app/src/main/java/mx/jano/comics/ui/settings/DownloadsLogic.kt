package mx.jano.comics.ui.settings

import mx.jano.comics.cache.PinProgress

/** En qué está la cola de fijados, para decirlo con texto (nunca solo con color). */
enum class PinQueueStatus { IDLE, DOWNLOADING, PAUSED, WAITING_WIFI, WAITING_NETWORK }

object DownloadsLogic {
    /**
     * Estado de la cola de fijados: sin cola, en pausa, esperando red (sin conexión, o con conexión de datos si los
     * fijados solo se bajan con Wi-Fi) o bajando.
     */
    fun pinStatus(
        progress: PinProgress?,
        paused: Boolean,
        online: Boolean,
        unmetered: Boolean,
        unmeteredOnly: Boolean,
    ): PinQueueStatus = when {
        progress == null -> PinQueueStatus.IDLE
        paused -> PinQueueStatus.PAUSED
        !online -> PinQueueStatus.WAITING_NETWORK
        unmeteredOnly && !unmetered -> PinQueueStatus.WAITING_WIFI
        else -> PinQueueStatus.DOWNLOADING
    }
}
