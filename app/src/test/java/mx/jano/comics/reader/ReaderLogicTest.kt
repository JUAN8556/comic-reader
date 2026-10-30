package mx.jano.comics.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ReaderLogicTest {
    private val w = 900f

    @Test
    fun tercios_en_lectura_occidental() {
        assertEquals(TapAction.PREVIOUS, TapZones.actionFor(10f, w, rtl = false))
        assertEquals(TapAction.PREVIOUS, TapZones.actionFor(299f, w, rtl = false))
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(300f, w, rtl = false))
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(450f, w, rtl = false))
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(600f, w, rtl = false))
        assertEquals(TapAction.NEXT, TapZones.actionFor(601f, w, rtl = false))
        assertEquals(TapAction.NEXT, TapZones.actionFor(899f, w, rtl = false))
    }

    @Test
    fun en_manga_los_laterales_se_invierten_y_el_centro_no() {
        assertEquals(TapAction.NEXT, TapZones.actionFor(10f, w, rtl = true))
        assertEquals(TapAction.PREVIOUS, TapZones.actionFor(890f, w, rtl = true))
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(450f, w, rtl = true))
    }

    @Test
    fun sin_ancho_medido_todo_es_centro() {
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(5f, 0f, rtl = false))
        assertEquals(TapAction.TOGGLE_OVERLAY, TapZones.actionFor(Float.NaN, w, rtl = false))
    }

    @Test
    fun la_pagina_destino_no_se_sale_del_archivo() {
        assertNull(TapZones.targetPage(TapAction.PREVIOUS, 0, 10))
        assertEquals(0, TapZones.targetPage(TapAction.PREVIOUS, 1, 10))
        assertEquals(5, TapZones.targetPage(TapAction.NEXT, 4, 10))
        assertNull(TapZones.targetPage(TapAction.NEXT, 9, 10))
        assertNull(TapZones.targetPage(TapAction.TOGGLE_OVERLAY, 3, 10))
        assertNull(TapZones.targetPage(TapAction.NEXT, 0, 0))
    }

    @Test
    fun extension_de_la_pagina_en_cache() {
        assertEquals("jpg", PageFiles.extensionFor("Batman 001/p01.JPG"))
        assertEquals("webp", PageFiles.extensionFor("scans\\002.webp"))
        assertEquals("png", PageFiles.extensionFor("page-0001.png"))
        assertEquals("img", PageFiles.extensionFor("sin_extension"))
        assertEquals("img", PageFiles.extensionFor("carpeta.v2/sin_extension"))
        assertEquals("img", PageFiles.extensionFor("raro.j p g"))
        assertEquals("img", PageFiles.extensionFor("demasiado.largaext"))
        assertEquals("img", PageFiles.extensionFor("punto_final."))
    }

    @Test
    fun fichero_de_cache_por_indice() {
        val dir = File("/tmp/paginas")
        assertEquals(File(dir, "0.jpg"), PageFiles.fileFor(dir, 0, "001.jpg"))
        assertEquals(File(dir, "41.img"), PageFiles.fileFor(dir, 41, "Thumbs"))
    }
}
