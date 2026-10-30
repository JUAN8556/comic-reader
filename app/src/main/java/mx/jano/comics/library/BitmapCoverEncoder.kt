package mx.jano.comics.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import mx.jano.comics.settings.AtomicFiles
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Portada a WebP de [targetWidth] px de ancho (alto proporcional) con Bitmap de Android.
 * Decodifica ya reducida (`inSampleSize` en potencias de 2) para no cargar en memoria una página de 4000 px, y
 * luego escala al ancho exacto. Si la imagen ya es pequeña (JPEG/PNG/WebP de hasta [KEEP_AS_IS_BYTES] y no mucho
 * más ancha que el objetivo), se guarda tal cual: Coil decodifica por contenido, no por la extensión.
 */
internal class BitmapCoverEncoder(
    private val targetWidth: Int = 300,
    private val quality: Int = 80,
) : CoverEncoder {

    override fun encode(image: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return false
        if (image.size <= KEEP_AS_IS_BYTES && w <= targetWidth * 3 / 2 && bounds.outMimeType in KEEP_TYPES) {
            AtomicFiles.write(dest, image)
            return true
        }
        var sample = 1
        while (w / (sample * 2) >= targetWidth) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            image, 0, image.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return false
        val scaled = if (decoded.width > targetWidth) {
            val height = (decoded.height.toLong() * targetWidth / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, targetWidth, height, true)
        } else {
            decoded
        }
        try {
            val out = ByteArrayOutputStream(64 * 1024)
            if (!scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, quality, out)) return false
            AtomicFiles.write(dest, out.toByteArray())
            return true
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private companion object {
        const val KEEP_AS_IS_BYTES = 60 * 1024
        val KEEP_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
