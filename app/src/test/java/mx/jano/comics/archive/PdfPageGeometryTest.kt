package mx.jano.comics.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.abs
import kotlin.random.Random

class PdfPageGeometryTest {

    private fun ratio(w: Number, h: Number) = w.toDouble() / h.toDouble()

    @Test fun pagina_vertical_al_ancho_de_pantalla() {
        val g = PdfPageGeometry.compute(1000, 1500, 1440)
        assertEquals(1440, g.widthPx)
        assertEquals(2160, g.heightPx)
        assertEquals(1.44f, g.scale, 1e-4f)
        // Comic estándar en puntos (6,625 × 10,25 in).
        val us = PdfPageGeometry.compute(477, 738, PdfComicArchive.DEFAULT_TARGET_WIDTH)
        assertEquals(1440, us.widthPx)
        assertTrue(abs(ratio(us.widthPx, us.heightPx) - ratio(477, 738)) < 0.002)
    }

    @Test fun tira_alta_conserva_la_proporcion() {
        // Webtoon: 800 × 30.000 pt. A 1440 de ancho serían 54.000 px de alto: se reduce la escala, no se deforma.
        val g = PdfPageGeometry.compute(800, 30_000, 1440)
        assertTrue("alto ${g.heightPx}", g.heightPx <= PdfPageGeometry.MAX_SIDE)
        assertTrue(g.widthPx.toLong() * g.heightPx <= PdfPageGeometry.MAX_PIXELS + g.widthPx + g.heightPx)
        assertEquals(ratio(800, 30_000), ratio(g.widthPx, g.heightPx), 0.002)
        assertEquals(g.widthPx / 800.0, g.scale.toDouble(), 0.01)
        // La versión anterior topaba el alto a 4 × ancho y deformaba: 1440 × 5760 (proporción 0,25 ≠ 0,027).
        assertTrue(ratio(g.widthPx, g.heightPx) < 0.03)
    }

    @Test fun tira_moderada_entera_al_ancho_si_cabe() {
        val g = PdfPageGeometry.compute(1000, 6000, 1440)   // 1440 × 8640 = 12,4 Mpx: cabe
        assertEquals(1440, g.widthPx)
        assertEquals(8640, g.heightPx)
    }

    @Test fun pliego_apaisado_por_el_lado_corto() {
        val g = PdfPageGeometry.compute(1500, 1000, 1440)
        assertEquals(1000 * g.scale, g.heightPx.toFloat(), 1f)
        assertEquals(1440, g.heightPx)
        assertEquals(2160, g.widthPx)
        // Panorámica muy ancha: el lado largo, como mucho 2,5 × 1440.
        val pano = PdfPageGeometry.compute(5000, 1000, 1440)
        assertEquals(3600, pano.widthPx)
        assertEquals(720, pano.heightPx)
    }

    @Test fun medidas_absurdas_no_revientan() {
        val zero = PdfPageGeometry.compute(0, 0, 1440)
        assertTrue(zero.widthPx >= 1 && zero.heightPx >= 1)
        val huge = PdfPageGeometry.compute(100_000, 100_000, 1440)
        assertEquals(1440, huge.widthPx)
        val tiny = PdfPageGeometry.compute(10, 15, 1440)
        assertEquals(1440, tiny.widthPx)
        assertEquals(2160, tiny.heightPx)
    }

    @Test fun nombre_de_pagina_jpg() {
        assertEquals("jpg", mx.jano.comics.reader.PageFiles.extensionFor("0001.jpg"))
    }

    /**
     * Medida del tamaño: una página 1000 × 1500 pt renderizada a la geometría real (1440 × 2160) y codificada a JPEG
     * calidad 0,85 queda por debajo de 1 MB. En la JVM no hay PdfRenderer ni Bitmap: se pinta una página de cómic
     * sintética con Java2D (viñetas, tramas, degradados, rotulación y grano de escaneo) y se codifica con el JPEG
     * de ImageIO a la misma calidad. Es un sustituto: la medida en la tablet queda pendiente (ver el informe).
     */
    @Test fun pagina_1000x1500_en_jpeg_85_pesa_menos_de_1_mb() {
        val g = PdfPageGeometry.compute(1000, 1500, PdfComicArchive.DEFAULT_TARGET_WIDTH)
        val img = syntheticComicPage(g.widthPx, g.heightPx)
        val bytes = jpeg(img, PdfComicArchive.JPEG_QUALITY / 100f)
        println("página sintética ${g.widthPx}×${g.heightPx} → JPEG q${PdfComicArchive.JPEG_QUALITY}: ${bytes.size / 1024} KB")
        assertTrue("${bytes.size} bytes", bytes.size < 1_000_000)
        // Y el PNG que se escribía antes (a 2000 px) era bastante mayor: el cambio sí ahorra.
        val old = PdfPageGeometry.compute(1000, 1500, 2000)
        val png = ByteArrayOutputStream().also { ImageIO.write(syntheticComicPage(old.widthPx, old.heightPx), "png", it) }.size()
        println("antes: PNG ${old.widthPx}×${old.heightPx}: ${png / 1024} KB")
        assertTrue(png > bytes.size)
    }

    private fun jpeg(img: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { ios ->
            writer.output = ios
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
            writer.write(null, IIOImage(img, null, null), param)
            writer.dispose()
        }
        return out.toByteArray()
    }

    private fun syntheticComicPage(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(250, 247, 238)
        g.fillRect(0, 0, w, h)
        val rnd = Random(2026)
        val margin = w / 24
        val rows = 3
        val rowH = (h - margin * (rows + 1)) / rows
        for (r in 0 until rows) {
            val cols = 1 + r % 2 + 1
            val colW = (w - margin * (cols + 1)) / cols
            for (c in 0 until cols) {
                val x = margin + c * (colW + margin)
                val y = margin + r * (rowH + margin)
                g.paint = GradientPaint(
                    x.toFloat(), y.toFloat(), Color(rnd.nextInt(80, 230), rnd.nextInt(80, 230), rnd.nextInt(80, 230)),
                    (x + colW).toFloat(), (y + rowH).toFloat(), Color(rnd.nextInt(30, 200), rnd.nextInt(30, 200), rnd.nextInt(30, 200)),
                )
                g.fillRect(x, y, colW, rowH)
                // Figuras con línea de tinta.
                repeat(6) {
                    g.color = Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                    val fx = x + rnd.nextInt(colW / 2)
                    val fy = y + rnd.nextInt(rowH / 2)
                    g.fillOval(fx, fy, rnd.nextInt(40, colW / 2), rnd.nextInt(40, rowH / 2))
                    g.color = Color.BLACK
                    g.stroke = BasicStroke(rnd.nextInt(2, 6).toFloat())
                    g.drawOval(fx, fy, rnd.nextInt(40, colW / 2), rnd.nextInt(40, rowH / 2))
                }
                // Trama de puntos (Ben-Day) en una zona.
                g.color = Color(30, 30, 30)
                var ty = y + rowH / 2
                while (ty < y + rowH - 10) {
                    var tx = x + 10
                    while (tx < x + colW / 2) {
                        g.fillOval(tx, ty, 4, 4)
                        tx += 9
                    }
                    ty += 9
                }
                // Globo con rotulación.
                g.color = Color.WHITE
                g.fillRoundRect(x + 20, y + 20, colW / 2, rowH / 4, 60, 60)
                g.color = Color.BLACK
                g.stroke = BasicStroke(3f)
                g.drawRoundRect(x + 20, y + 20, colW / 2, rowH / 4, 60, 60)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 26)
                for (line in 0 until 3) g.drawString("¡ESTO ES UNA PRUEBA DE ROTULACIÓN ${r}${c}${line}!", x + 40, y + 60 + line * 32)
                g.stroke = BasicStroke(8f)
                g.drawRect(x, y, colW, rowH)
            }
        }
        g.dispose()
        // Grano de escaneo: ruido suave en toda la página.
        for (y in 0 until h) for (x in 0 until w) {
            val n = rnd.nextInt(-6, 7)
            val p = img.getRGB(x, y)
            val r = ((p shr 16 and 0xFF) + n).coerceIn(0, 255)
            val gg = ((p shr 8 and 0xFF) + n).coerceIn(0, 255)
            val b = ((p and 0xFF) + n).coerceIn(0, 255)
            img.setRGB(x, y, (r shl 16) or (gg shl 8) or b)
        }
        return img
    }
}
