package mx.jano.comics.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFormatTest {
    private val mb = 1024L * 1024

    @Test
    fun candidatos_a_muestra() {
        assertTrue(ProbeFormat.isSampleCandidate("Batman 001.cbr", isFolder = false, size = 30 * mb))
        assertTrue(ProbeFormat.isSampleCandidate("ONE PIECE 01.CBZ", isFolder = false, size = 80 * mb))
        assertTrue(ProbeFormat.isSampleCandidate("Maus.pdf", isFolder = false, size = 1))
        assertFalse(ProbeFormat.isSampleCandidate("Grande.cbr", isFolder = false, size = 80 * mb + 1))
        assertFalse(ProbeFormat.isSampleCandidate("Sin tamaño.cbz", isFolder = false, size = null))
        assertFalse(ProbeFormat.isSampleCandidate("Vacío.cbz", isFolder = false, size = 0))
        assertFalse(ProbeFormat.isSampleCandidate("Carpeta.cbr", isFolder = true, size = 10))
        assertFalse(ProbeFormat.isSampleCandidate("portada.jpg", isFolder = false, size = 10))
        assertFalse(ProbeFormat.isSampleCandidate("tomo.cb7", isFolder = false, size = 10))
    }

    @Test
    fun extension_de_la_muestra() {
        assertEquals("cbr", ProbeFormat.sampleExtension("X.CBR"))
        assertEquals("pdf", ProbeFormat.sampleExtension("x.pdf "))
        assertNull(ProbeFormat.sampleExtension("x.zip"))
        assertNull(ProbeFormat.sampleExtension("cbr"))
    }

    @Test
    fun la_carpeta_comics_se_reconoce_normalizada() {
        val nfc = "Cómics"
        val nfd = "Cómics"
        assertEquals(ProbeFormat.folderKey(nfc), ProbeFormat.folderKey(nfd))
        assertEquals(ProbeFormat.folderKey(nfc), ProbeFormat.folderKey("cómics "))
        assertFalse(ProbeFormat.folderKey(nfc) == ProbeFormat.folderKey("Comics"))
    }

    @Test
    fun pistas_del_modo_de_papel() {
        assertTrue(ProbeFormat.looksLikeInkSetting("tct_ink_mode_enable"))
        assertTrue(ProbeFormat.looksLikeInkSetting("NXTPAPER_mode"))
        assertTrue(ProbeFormat.looksLikeInkSetting("color_paper_mode"))
        assertTrue(ProbeFormat.looksLikeInkSetting("nxtpaper_ink_style_state"))
        assertTrue(ProbeFormat.looksLikeInkSetting("adv_color_mode_value"))
        assertTrue(ProbeFormat.looksLikeInkSetting("eyeprotect_status"))
        assertTrue(ProbeFormat.looksLikeInkSetting("reading_mode_be_on"))
        assertTrue(ProbeFormat.looksLikeInkSetting("installed_theme"))
        assertFalse(ProbeFormat.looksLikeInkSetting("screen_brightness"))
        assertFalse(ProbeFormat.looksLikeInkSetting("font_scale"))
    }

    @Test
    fun formatos_con_coma_decimal() {
        assertEquals("1,5 MB", ProbeFormat.mb(mb + mb / 2))
        assertEquals("2,0 MB/s", ProbeFormat.mbPerSecond(4 * mb, 2000))
        assertEquals("— MB/s", ProbeFormat.mbPerSecond(4 * mb, 0))
        assertEquals("1,25 GB", ProbeFormat.gb(1024L * mb + 256 * mb))
        assertEquals("0,5 s", ProbeFormat.seconds(500))
    }

    @Test
    fun modo_de_navegacion() {
        assertEquals("2 (gestos)", ProbeFormat.navigationModeLabel("2"))
        assertEquals("0 (tres botones)", ProbeFormat.navigationModeLabel("0"))
        assertEquals("sin valor", ProbeFormat.navigationModeLabel(null))
    }

    @Test
    fun paginas_que_mide_abrir_archivo() {
        assertEquals(emptyList<Int>(), ProbeFormat.probePages(0))
        assertEquals(listOf(0), ProbeFormat.probePages(1))
        assertEquals(listOf(0, 1), ProbeFormat.probePages(2))
        assertEquals(listOf(0, 1, 2), ProbeFormat.probePages(3))
        assertEquals(listOf(0, 12, 23), ProbeFormat.probePages(24))
    }

    @Test
    fun pagina_hacia_atras() {
        assertNull(ProbeFormat.backwardProbePage(3))
        assertEquals(1, ProbeFormat.backwardProbePage(4))
        assertEquals(29, ProbeFormat.backwardProbePage(60))
        assertFalse(ProbeFormat.probePages(60).contains(29))
    }

    @Test
    fun veredicto_de_la_reanudacion() {
        assertTrue(ProbeFormat.resumeVerdict(500, 500, 100).startsWith("reanudó: sí. Range honrado"))
        assertTrue(ProbeFormat.resumeVerdict(500, 500, 0).startsWith("reanudó: sí, sin bajar nada"))
        // C1: un 200 que reescribe desde cero da el mismo tamaño y md5 que un 206, pero no es reanudar.
        assertTrue(ProbeFormat.resumeVerdict(500, 0, 1000).startsWith("reanudó: NO."))
        assertTrue(ProbeFormat.resumeVerdict(500, 200, 10).startsWith("reanudó: NO como se esperaba"))
        assertTrue(ProbeFormat.resumeVerdict(500, -1, 10).startsWith("reanudó: ¿?"))
    }
}
