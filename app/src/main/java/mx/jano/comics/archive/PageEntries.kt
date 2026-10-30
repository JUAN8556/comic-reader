package mx.jano.comics.archive

import java.text.Normalizer
import java.util.Locale

/** Extensiones que cuentan como página (en minúsculas). */
internal val PAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

/**
 * Ruta de una entrada tal y como se enseña y se ordena: separador «/» (los RAR de Windows traen «\»),
 * sin «./» ni segmentos vacíos.
 */
internal fun normalizeEntryPath(raw: String): String =
    raw.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")

/**
 * ¿Esta entrada es una página? Solo imágenes; fuera directorios, `__MACOSX/`, todo lo que empiece por «.»
 * (en cualquier segmento: `.DS_Store`, `._1.jpg`, `.thumbs/…`) y `Thumbs.db`.
 */
internal fun isPageEntry(rawPath: String, isDirectory: Boolean): Boolean {
    if (isDirectory) return false
    val path = normalizeEntryPath(rawPath)
    if (path.isEmpty() || rawPath.endsWith("/") || rawPath.endsWith("\\")) return false
    val segments = path.split('/')
    if (segments.any { it.equals("__MACOSX", ignoreCase = true) || it.startsWith(".") }) return false
    val base = segments.last()
    if (base.equals("Thumbs.db", ignoreCase = true)) return false
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.length - 1) return false
    return base.substring(dot + 1).lowercase(Locale.ROOT) in PAGE_EXTENSIONS
}

/**
 * Orden natural por ruta completa: segmento a segmento, los números por su valor (1, 2, 10; no 1, 10, 2),
 * sin distinguir mayúsculas y con los acentos en NFC (los nombres que vienen de la Mac llegan en NFD).
 * Empate final: la cadena original, para que el orden sea total y estable.
 */
internal object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val sa = key(a)
        val sb = key(b)
        for (i in 0 until minOf(sa.size, sb.size)) {
            val c = compareSegment(sa[i], sb[i])
            if (c != 0) return c
        }
        if (sa.size != sb.size) return sa.size - sb.size
        return a.compareTo(b)
    }

    private fun key(path: String): List<String> =
        Normalizer.normalize(normalizeEntryPath(path), Normalizer.Form.NFC).lowercase(Locale.ROOT).split('/')

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** Compara dos segmentos ya en minúsculas, troceando en series de dígitos y de no dígitos. */
    internal fun compareSegment(a: String, b: String): Int {
        var i = 0
        var j = 0
        var zerosTieBreak = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val startA = i
                val startB = j
                while (i < a.length && a[i].isAsciiDigit()) i++
                while (j < b.length && b[j].isAsciiDigit()) j++
                var za = startA
                while (za < i - 1 && a[za] == '0') za++
                var zb = startB
                while (zb < j - 1 && b[zb] == '0') zb++
                val lenA = i - za
                val lenB = j - zb
                if (lenA != lenB) return lenA - lenB
                for (k in 0 until lenA) {
                    val d = a[za + k] - b[zb + k]
                    if (d != 0) return d
                }
                // Mismo valor («01» y «1»): manda lo que venga detrás; si todo empata, menos ceros delante primero.
                if (zerosTieBreak == 0) zerosTieBreak = (za - startA) - (zb - startB)
            } else {
                if (ca != cb) return ca - cb
                i++
                j++
            }
        }
        val rest = (a.length - i) - (b.length - j)
        return if (rest != 0) rest else zerosTieBreak
    }
}

/** Una página: su nombre (ruta normalizada dentro del archivo) y lo que el motor necesite para sacarla. */
internal class PageRef<T>(val name: String, val handle: T)

/** Filtra y ordena entradas en orden natural. [name] da la ruta cruda; [isDirectory], si es carpeta. */
internal fun <T> pagesOf(entries: Iterable<T>, name: (T) -> String, isDirectory: (T) -> Boolean): List<PageRef<T>> =
    entries.asSequence()
        .filter { isPageEntry(name(it), isDirectory(it)) }
        .map { PageRef(normalizeEntryPath(name(it)), it) }
        .sortedWith { x, y -> NaturalOrder.compare(x.name, y.name) }
        .toList()
