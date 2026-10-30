package mx.jano.comics.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import mx.jano.comics.archive.Rar4StoredWriter
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale
import kotlin.random.Random

/**
 * RAR4 SÓLIDO sintético para la tablet: en la biblioteca no hay ningún RAR sólido (0 de 159 en el informe local),
 * así que sin esto la parte «RAR4 sólido» de la incógnita 3 no se puede medir en el Helio G100.
 *
 * 60 páginas JPEG de 1200×1800 con ruido en bloques (para que pesen como una página escaneada, del orden de 0,5-1 MB)
 * y el número bien grande, escritas como entradas GUARDADAS con las banderas de sólido reales ([Rar4StoredWriter]).
 * Mide el camino sólido de junrar (salto entre entradas, reinicio al ir hacia atrás) y la caché de sólidos
 * (cada página escrita dos veces) en el almacenamiento de la tablet. NO mide la descompresión LZ de un sólido
 * comprimido real: eso sigue pendiente de que aparezca uno.
 */
internal object SyntheticComic {
    const val PAGES = 60
    const val FILE_NAME = "solido-sintetico-60.cbr"
    const val ID = "sintetico-solido-60"
    private const val WIDTH = 1200
    private const val HEIGHT = 1800
    private const val BLOCK = 12
    private const val JPEG_QUALITY = 90

    /** Devuelve el .cbr; lo genera la primera vez (unos segundos) y luego lo reutiliza. */
    fun ensure(dir: File, log: (String) -> Unit): File {
        val file = File(dir, FILE_NAME)
        if (file.isFile && file.length() > 0) {
            log("sólido sintético ya generado: ${ProbeFormat.mb(file.length())}")
            return file
        }
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) error("no se pudo crear $dir")
        val tmp = File(dir, "$FILE_NAME.part")
        val base = noise(WIDTH, HEIGHT, Random(2026))
        var pageBytes = 0L
        try {
            val entries = sequence {
                for (i in 1..PAGES) {
                    val jpeg = page(base, i)
                    pageBytes += jpeg.size
                    if (i == 1 || i % 20 == 0) log("  generada pág. $i/$PAGES (${jpeg.size / 1024} KB)")
                    yield(Rar4StoredWriter.Entry(String.format(Locale.ROOT, "%03d.jpg", i), jpeg))
                }
            }
            Rar4StoredWriter.write(tmp, entries.asIterable(), solid = true)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            base.recycle()
            tmp.delete()
        }
        log("sólido sintético generado: $PAGES páginas JPEG guardadas (${ProbeFormat.mb(pageBytes)} de imágenes), ${ProbeFormat.mb(file.length())} en disco")
        return file
    }

    /** Fondo de bloques de color al azar: se comprime mal en JPEG, como una página escaneada. */
    private fun noise(w: Int, h: Int, random: Random): Bitmap {
        val pixels = IntArray(w * h)
        for (by in 0 until h step BLOCK) {
            for (bx in 0 until w step BLOCK) {
                val c = Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256))
                for (y in by until minOf(by + BLOCK, h)) {
                    val row = y * w
                    for (x in bx until minOf(bx + BLOCK, w)) pixels[row + x] = c
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun page(base: Bitmap, number: Int): ByteArray {
        val bmp = base.copy(Bitmap.Config.ARGB_8888, true)
        try {
            val canvas = Canvas(bmp)
            val panel = Paint().apply { color = Color.WHITE }
            val border = Paint().apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 12f
            }
            val left = WIDTH * 0.15f
            val top = HEIGHT * 0.35f
            val right = WIDTH * 0.85f
            val bottom = HEIGHT * 0.65f
            canvas.drawRect(left, top, right, bottom, panel)
            canvas.drawRect(left, top, right, bottom, border)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 260f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("$number", WIDTH / 2f, HEIGHT / 2f + 60f, text)
            text.textSize = 56f
            canvas.drawText("de $PAGES · RAR4 sólido sintético", WIDTH / 2f, bottom - 40f, text)
            val out = ByteArrayOutputStream(1 shl 20)
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) error("no se pudo codificar la pág. $number")
            return out.toByteArray()
        } finally {
            bmp.recycle()
        }
    }
}
