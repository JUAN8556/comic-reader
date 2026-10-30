package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageEntriesTest {
    private fun sorted(vararg names: String) = names.sortedWith(NaturalOrder)

    @Test fun numeros_por_su_valor() {
        assertEquals(listOf("1.jpg", "2.jpg", "10.jpg"), sorted("10.jpg", "2.jpg", "1.jpg"))
        assertEquals(listOf("p9.jpg", "p10.jpg", "p100.jpg"), sorted("p100.jpg", "p10.jpg", "p9.jpg"))
        assertEquals(listOf("4.jpg", "30.jpg", "200.jpg"), sorted("30.jpg", "200.jpg", "4.jpg"))
        assertEquals(listOf("page 2 b.jpg", "page 10 a.jpg"), sorted("page 10 a.jpg", "page 2 b.jpg"))
    }

    @Test fun ceros_a_la_izquierda_y_mayusculas() {
        assertEquals(listOf("001.jpg", "02.jpg", "3.jpg", "010.jpg"), sorted("010.jpg", "3.jpg", "02.jpg", "001.jpg"))
        assertEquals(listOf("a1.jpg", "B2.jpg", "c3.jpg"), sorted("c3.jpg", "B2.jpg", "a1.jpg"))
        // Mismo valor numérico: primero el que tiene menos ceros, y el orden es total.
        assertEquals(listOf("1.jpg", "01.jpg", "001.jpg"), sorted("001.jpg", "01.jpg", "1.jpg"))
    }

    @Test fun por_ruta_completa_segmento_a_segmento() {
        assertEquals(
            listOf("1.jpg", "2.jpg", "10.jpg", "carpeta/3.png"),
            sorted("carpeta/3.png", "10.jpg", "2.jpg", "1.jpg"),
        )
        assertEquals(
            listOf("Vol 2/01.jpg", "Vol 2/10.jpg", "Vol 10/01.jpg"),
            sorted("Vol 10/01.jpg", "Vol 2/10.jpg", "Vol 2/01.jpg"),
        )
        // «Vol 1/» antes que «Vol 1 extra/», aunque « » < «/» en ASCII.
        assertEquals(listOf("Vol 1/01.jpg", "Vol 1 extra/01.jpg"), sorted("Vol 1 extra/01.jpg", "Vol 1/01.jpg"))
        // Separador de Windows (RAR) igual que «/».
        assertEquals(listOf("a\\2.jpg", "a/10.jpg"), sorted("a/10.jpg", "a\\2.jpg"))
    }

    @Test fun acentos_nfd_y_nfc_ordenan_igual() {
        val nfc = "Página 2.jpg" // «á» compuesta
        val nfd = "Página 10.jpg" // «a» + acento combinante (como llegan de la Mac)
        assertEquals(listOf(nfc, nfd), sorted(nfd, nfc))
    }

    @Test fun filtro_de_paginas() {
        for (ok in listOf("1.jpg", "a/B.JPEG", "x.png", "x.webp", "x.gif", "x.bmp", "x.avif", "c\\d\\01.Jpg", "./3.jpg")) {
            assertTrue(ok, isPageEntry(ok, isDirectory = false))
        }
        for (no in listOf(
            "__MACOSX/._1.jpg", "a/__MACOSX/1.jpg", ".DS_Store", "._1.jpg", "a/.hidden.jpg", ".thumbs/1.jpg",
            "Thumbs.db", "leeme.txt", "ComicInfo.xml", "sin_extension", "punto.", ".jpg", "carpeta/",
        )) {
            assertFalse(no, isPageEntry(no, isDirectory = false))
        }
        assertFalse(isPageEntry("carpeta.jpg", isDirectory = true))
    }

    @Test fun nombres_normalizados() {
        assertEquals("a/b/1.jpg", normalizeEntryPath("a\\b\\1.jpg"))
        assertEquals("a/1.jpg", normalizeEntryPath("./a//1.jpg"))
    }
}
