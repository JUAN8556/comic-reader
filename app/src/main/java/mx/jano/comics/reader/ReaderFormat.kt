package mx.jano.comics.reader

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/** Formatos del lector (puros: el locale se pasa para probarlos en la JVM). */
object ReaderFormat {
    private const val MB = 1024.0 * 1024.0

    /** «12,3 MB» / «12.3 MB» según el locale; una cifra decimal. */
    fun megabytes(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return nf.format(bytes.coerceAtLeast(0) / MB) + " MB"
    }

    /** «12,3 de 40,0 MB», o solo lo bajado si no se sabe el total. */
    fun downloaded(bytes: Long, total: Long?, locale: Locale = Locale.getDefault()): String {
        if (total == null || total <= 0) return megabytes(bytes, locale)
        val nf = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        return nf.format(bytes.coerceAtLeast(0) / MB) + " de " + nf.format(total / MB) + " MB"
    }

    /** «1.840 KB/s» (KB de 1024 bytes, sin decimales, con separador de miles del locale). */
    fun speed(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String {
        val nf = NumberFormat.getIntegerInstance(locale)
        return nf.format((bytesPerSecond.coerceAtLeast(0) / 1024.0).roundToLong()) + " KB/s"
    }

    /** Fracción 0..1 de la descarga, o null si no se sabe el total (anillo indeterminado). */
    fun fraction(bytes: Long, total: Long?): Float? {
        if (total == null || total <= 0) return null
        return (bytes.toDouble() / total).toFloat().coerceIn(0f, 1f)
    }

    /** Segundos que faltan a la velocidad actual, o null si no se puede estimar. */
    fun secondsLeft(bytes: Long, total: Long?, bytesPerSecond: Long): Long? {
        if (total == null || total <= 0 || bytesPerSecond <= 0) return null
        val left = (total - bytes).coerceAtLeast(0)
        return (left.toDouble() / bytesPerSecond).roundToLong()
    }

    /** «quedan 25 s» / «quedan 2 min». */
    fun timeLeft(seconds: Long): String = when {
        seconds < 60 -> "quedan $seconds s"
        else -> "quedan ${(seconds + 30) / 60} min"
    }

    /** Etiqueta de páginas del pliego: «5 / 36» o «5-6 / 36» (1-based). */
    fun pagesLabel(firstPage: Int, lastPage: Int, pageCount: Int): String {
        val a = firstPage + 1
        val b = lastPage + 1
        return if (b > a) "$a-$b / $pageCount" else "$a / $pageCount"
    }

    /** Porcentaje entero para el centro del anillo: «37 %». */
    fun percent(fraction: Float): String = "${(fraction * 100).toInt().coerceIn(0, 100)} %"
}
