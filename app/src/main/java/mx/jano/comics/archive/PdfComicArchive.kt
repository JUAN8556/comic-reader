package mx.jano.comics.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF con el [PdfRenderer] de la plataforma (módulo Mainline en Android 15). Cada página se renderiza a un JPEG de
 * calidad [JPEG_QUALITY] sobre fondo blanco, al ancho de pantalla [targetWidthPx] (1440 en la tablet), con una
 * [Matrix] de escala uniforme: la proporción de la página se conserva siempre, también en las tiras altas, que se
 * reducen enteras en vez de deformarse (ver [PdfPageGeometry]).
 *
 * PdfRenderer no es seguro entre hilos y solo admite una página abierta a la vez; además PDFium comparte estado entre
 * documentos, así que TODO render de la app pasa por un único cerrojo global ([RENDER_LOCK]): el lector y la portada
 * de otro PDF nunca renderizan a la vez.
 */
internal class PdfComicArchive private constructor(
    private val source: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    internal val targetWidthPx: Int,
) : ComicArchive {

    @Volatile private var closed = false

    override val kind: ArchiveKind get() = ArchiveKind.PDF
    override val pageCount: Int = renderer.pageCount

    /** Los PDF no tienen nombres de página: «0001.jpg», «0002.jpg»… (la extensión dice lo que se escribe). */
    override fun pageName(index: Int): String {
        require(index in 0 until pageCount) { "Página fuera de rango: $index" }
        return String.format(Locale.ROOT, "%04d.jpg", index + 1)
    }

    override fun extractPage(index: Int, dest: File) {
        require(index in 0 until pageCount) { "Página fuera de rango: $index" }
        synchronized(RENDER_LOCK) {
            if (closed) throw IOException(ArchiveTexts.closed(source))
            val page = renderer.openPage(index)
            try {
                val g = PdfPageGeometry.compute(page.width, page.height, targetWidthPx)
                val bitmap = Bitmap.createBitmap(g.widthPx, g.heightPx, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    val matrix = Matrix().apply { setScale(g.scale, g.scale) }
                    page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    writeAtomically(dest) { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                            throw IOException("No se pudo escribir el JPEG de la página ${index + 1}")
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                page.close()
            }
        }
    }

    override fun close() {
        synchronized(RENDER_LOCK) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        const val DEFAULT_TARGET_WIDTH = 1440
        const val JPEG_QUALITY = 85

        /** Un solo render a la vez en todo el proceso (ver la clase). */
        private val RENDER_LOCK = Any()

        fun open(file: File, targetWidthPx: Int = DEFAULT_TARGET_WIDTH): PdfComicArchive {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                synchronized(RENDER_LOCK) { PdfRenderer(descriptor) }
            } catch (e: SecurityException) {
                // PdfRenderer lanza SecurityException con los PDF protegidos con contraseña.
                descriptor.close()
                throw EncryptedArchiveException(ArchiveKind.PDF, ArchiveTexts.encryptedPdf(file), e)
            } catch (e: IOException) {
                descriptor.close()
                throw CorruptArchiveException(ArchiveKind.PDF, ArchiveTexts.corrupt(file, e.message), e)
            }
            return PdfComicArchive(file, descriptor, renderer, targetWidthPx)
        }
    }
}

/**
 * Tamaño del bitmap de una página PDF y la escala uniforme con la que se pinta (puro, con pruebas JVM).
 *
 * - Vertical (alto ≥ ancho): ancho = [targetWidth]; alto proporcional.
 * - Apaisada (un pliego): el lado CORTO = [targetWidth], para que en horizontal (2200 px de ancho en la tablet) el
 *   pliego no quede borroso; el lado largo, como mucho [MAX_LANDSCAPE_FACTOR] × [targetWidth].
 * - Topes: ningún lado mayor que [MAX_SIDE] y no más de [MAX_PIXELS] píxeles (~64 MB en ARGB_8888). Si una tira
 *   altísima los pasa, se reduce la ESCALA (la página entera, más estrecha), nunca se deforma.
 */
internal object PdfPageGeometry {
    const val MAX_SIDE = 16_384
    const val MAX_PIXELS = 16_000_000L
    const val MAX_LANDSCAPE_FACTOR = 2.5f

    data class Geometry(val widthPx: Int, val heightPx: Int, val scale: Float)

    /** [pageWidth] y [pageHeight] en puntos PDF (lo que dan `Page.getWidth()` y `getHeight()`). */
    fun compute(pageWidth: Int, pageHeight: Int, targetWidth: Int): Geometry {
        val pw = pageWidth.coerceAtLeast(1).toDouble()
        val ph = pageHeight.coerceAtLeast(1).toDouble()
        var scale = if (pw > ph) {
            min(targetWidth / ph, targetWidth * MAX_LANDSCAPE_FACTOR / pw)
        } else {
            targetWidth / pw
        }
        scale = min(scale, MAX_SIDE / max(pw, ph))
        if (pw * ph * scale * scale > MAX_PIXELS) scale = sqrt(MAX_PIXELS / (pw * ph))
        val w = (pw * scale).roundToInt().coerceIn(1, MAX_SIDE)
        val h = (ph * scale).roundToInt().coerceIn(1, MAX_SIDE)
        return Geometry(w, h, scale.toFloat())
    }
}
