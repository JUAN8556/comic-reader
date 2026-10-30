package mx.jano.comics.archive

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * PDF con el [PdfRenderer] de la plataforma (módulo Mainline en Android 15). Cada página se renderiza a un PNG de
 * [targetWidthPx] de ancho (alto proporcional, con tope) sobre fondo blanco.
 * PdfRenderer no es seguro entre hilos y solo admite una página abierta a la vez: todo va con [lock].
 */
internal class PdfComicArchive private constructor(
    private val source: File,
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val targetWidthPx: Int,
) : ComicArchive {

    private val lock = Any()
    @Volatile private var closed = false

    override val kind: ArchiveKind get() = ArchiveKind.PDF
    override val pageCount: Int = renderer.pageCount

    /** Los PDF no tienen nombres de página: «0001.png», «0002.png»… (la extensión dice lo que se escribe). */
    override fun pageName(index: Int): String {
        require(index in 0 until pageCount) { "Página fuera de rango: $index" }
        return String.format(Locale.ROOT, "%04d.png", index + 1)
    }

    override fun extractPage(index: Int, dest: File) {
        require(index in 0 until pageCount) { "Página fuera de rango: $index" }
        synchronized(lock) {
            if (closed) throw IOException(ArchiveTexts.closed(source))
            val page = renderer.openPage(index)
            try {
                val width = targetWidthPx
                val pageW = page.width.coerceAtLeast(1)
                val pageH = page.height.coerceAtLeast(1)
                // Alto proporcional; tope de 4 veces el ancho para que una tira larguísima no agote la memoria.
                val height = (width.toLong() * pageH / pageW).coerceIn(1L, width * 4L).toInt()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    writeAtomically(dest) { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            throw IOException("No se pudo escribir el PNG de la página ${index + 1}")
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
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }

    companion object {
        fun open(file: File, targetWidthPx: Int): PdfComicArchive {
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(descriptor)
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
