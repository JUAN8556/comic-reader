package mx.jano.comics.ui.settings

import mx.jano.comics.cache.PinProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsLogicTest {
    private val queue = PinProgress(total = 10, done = 4, bytesDone = 400, bytesTotal = 1_000, current = "Akira 05.cbz")

    private fun status(progress: PinProgress? = queue, paused: Boolean = false, online: Boolean = true, unmetered: Boolean = true, wifiOnly: Boolean = true) =
        DownloadsLogic.pinStatus(progress, paused, online, unmetered, wifiOnly)

    @Test fun sinColaNoHayNada() = assertEquals(PinQueueStatus.IDLE, status(progress = null, paused = true, online = false))

    @Test fun laPausaMandaSobreLaRed() = assertEquals(PinQueueStatus.PAUSED, status(paused = true, online = false))

    @Test fun sinConexionEspera() = assertEquals(PinQueueStatus.WAITING_NETWORK, status(online = false, unmetered = false))

    @Test fun conDatosYSoloWifiEsperaWifi() {
        assertEquals(PinQueueStatus.WAITING_WIFI, status(unmetered = false))
        assertEquals(PinQueueStatus.DOWNLOADING, status(unmetered = false, wifiOnly = false))
    }

    @Test fun conWifiBaja() = assertEquals(PinQueueStatus.DOWNLOADING, status())
}
