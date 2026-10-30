package mx.jano.comics.library

import java.text.Normalizer
import java.util.Locale

/**
 * Plegado de texto para comparar nombres: sin acentos (NFD sin marcas combinantes), en minúsculas.
 * «Última» → «ultima», «Ñ» → «n».
 */
internal object TextFold {
    fun foldCodePoint(cp: Int): String {
        if (cp < 0x80) return Character.toChars(cp)[0].lowercaseChar().toString()
        val decomposed = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFD)
        val sb = StringBuilder(decomposed.length)
        for (ch in decomposed) {
            if (Character.getType(ch) != Character.NON_SPACING_MARK.toInt()) sb.append(ch)
        }
        return sb.toString().lowercase(Locale.ROOT)
    }

    fun fold(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            sb.append(foldCodePoint(cp))
            i += Character.charCount(cp)
        }
        return sb.toString()
    }
}

/**
 * Orden natural de nombres de la biblioteca: los números por su valor (2 < 10, «01» = «1»), sin distinguir
 * mayúsculas ni acentos, con «#» ignorado («Kraven #4» junto a «Kraven 4») y los espacios repetidos como uno.
 * Si dos nombres empatan así, decide la forma con acentos y, al final, la cadena tal cual: el orden es total y estable.
 */
internal object NaturalComparator : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        if (a == b) return 0
        val c = compareKeys(key(a), key(b))
        if (c != 0) return c
        val accented = Normalizer.normalize(a, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .compareTo(Normalizer.normalize(b, Normalizer.Form.NFC).lowercase(Locale.ROOT))
        if (accented != 0) return accented
        return a.compareTo(b)
    }

    /** Clave de comparación: plegada, sin «#», espacios colapsados y recortados. */
    internal fun key(s: String): String {
        val folded = TextFold.fold(s)
        val sb = StringBuilder(folded.length)
        var lastSpace = true
        for (ch in folded) {
            when {
                ch == '#' -> {}
                ch.isWhitespace() -> if (!lastSpace) { sb.append(' '); lastSpace = true }
                else -> { sb.append(ch); lastSpace = false }
            }
        }
        if (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
        return sb.toString()
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    /** Trocea en series de dígitos y de no dígitos; las de dígitos se comparan por valor (sin límite de longitud). */
    internal fun compareKeys(a: String, b: String): Int {
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

/**
 * «Smart naming» de Cover: el nombre de un número dentro de su estante, sin repetir el nombre del estante.
 *
 * - Sin la extensión de cómic (.cbr, .cbz, .pdf…; nunca se corta un «.La Caída» que no es extensión).
 * - Si el nombre (sin acentos, mayúsculas ni diferencias de separadores «.», «_», «-», «,» o espacios) empieza por
 *   el del estante, se quita ese prefijo y los separadores que sobren: «Death Note Tomo 12» en «Death Note» →
 *   «Tomo 12»; «Uncanny X-Men 174, October 1983» en «Uncanny X-Men» → «174, October 1983».
 * - También vale el estante sin su coletilla final entre paréntesis o corchetes («Hulk - La Caída del Panteón
 *   (Forum)» casa con «Hulk.La Caida Del Panteon 01») y un número de orden delante del nombre del fichero
 *   («00 Guardians of The Galaxy 07» en «Guardians of the Galaxy» → «07»).
 * - El prefijo tiene que acabar en frontera de palabra: «Bat» no se come el «Bat» de «Batman 01».
 * - Si no queda nada (el fichero se llama como el estante), se deja el nombre entero. Una coletilla suelta entre
 *   paréntesis o corchetes cuenta como «nada»: «Título (1999)» en «Título» (el número único de la migración) se
 *   queda «Título (1999)», no «(1999)».
 * - Los «_» se leen como espacios cuando el nombre no tiene ningún espacio («Batman_01» → «Batman 01»).
 */
internal object SmartNames {

    /** Extensiones que se quitan al enseñar el nombre (en minúsculas). */
    private val STRIP_EXTENSIONS = setOf("cbz", "cbr", "cb7", "cbt", "cba", "pdf", "zip", "rar", "7z", "tar", "epub")

    private const val SEPARATORS = " \t._-,:;–—~ "

    private fun isSeparator(ch: Char) = ch in SEPARATORS

    fun stripExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return fileName
        val ext = fileName.substring(dot + 1).lowercase(Locale.ROOT)
        return if (ext in STRIP_EXTENSIONS) fileName.substring(0, dot) else fileName
    }

    fun smartName(fileName: String, folderName: String): String {
        val raw = Normalizer.normalize(stripExtension(fileName), Normalizer.Form.NFC).trim()
        val stem = if ('_' in raw && ' ' !in raw) raw.replace('_', ' ').trim() else raw
        if (stem.isEmpty()) return fileName
        val folder = Normalizer.normalize(folderName, Normalizer.Form.NFC).trim()
        if (folder.isEmpty()) return stem

        val candidates = folderCandidates(folder)
        for (candidate in candidates) {
            val rest = stripPrefix(stem, candidate)
            if (isMeaningfulRest(rest)) return rest!!
        }
        // Número de orden delante («00 Guardians of The Galaxy 07»).
        LEADING_ORDER.find(stem)?.let { m ->
            val tail = stem.substring(m.range.last + 1)
            for (candidate in candidates) {
                val rest = stripPrefix(tail, candidate)
                if (isMeaningfulRest(rest)) return rest!!
            }
        }
        return stem
    }

    /** Lo que queda tras quitar el estante vale como nombre si, sin sus grupos entre paréntesis o corchetes, dice algo. */
    private fun isMeaningfulRest(rest: String?): Boolean =
        !rest.isNullOrEmpty() && rest.replace(GROUPS, "").any { it.isLetterOrDigit() }

    private val LEADING_ORDER = Regex("""^\d{1,4}[\s._-]+""")
    private val TRAILING_GROUP = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")

    /** El nombre del estante y, si tiene coletillas finales «(Forum)», «[2004]», también sin ellas. */
    private fun folderCandidates(folder: String): List<String> {
        val out = LinkedHashSet<String>()
        out += folder
        var current = folder
        while (true) {
            val stripped = current.replace(TRAILING_GROUP, "").trim()
            if (stripped == current || stripped.isEmpty()) break
            out += stripped
            current = stripped
        }
        return out.toList()
    }

    /** Texto plegado y, para cada carácter plegado, el índice del texto original justo detrás de él. */
    private class Folded(val text: String, val ends: IntArray)

    /** Pliega [s] con los separadores colapsados en un espacio y sin separadores al principio. */
    private fun foldForMatch(s: String): Folded {
        val sb = StringBuilder(s.length)
        val ends = ArrayList<Int>(s.length)
        var lastSep = true
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val next = i + Character.charCount(cp)
            for (ch in TextFold.foldCodePoint(cp)) {
                if (isSeparator(ch)) {
                    if (!lastSep) {
                        sb.append(' ')
                        ends += next
                        lastSep = true
                    } else if (sb.isNotEmpty()) {
                        ends[ends.size - 1] = next
                    }
                } else {
                    sb.append(ch)
                    ends += next
                    lastSep = false
                }
            }
            i = next
        }
        return Folded(sb.toString(), ends.toIntArray())
    }

    /** Lo que queda de [name] tras quitarle [prefix] (comparando plegado) y los separadores sobrantes; null si no empieza así. */
    private fun stripPrefix(name: String, prefix: String): String? {
        val p = foldForMatch(prefix).text.trimEnd()
        if (p.isEmpty()) return null
        val n = foldForMatch(name)
        if (!n.text.startsWith(p)) return null
        if (n.text.length > p.length && p.last().isLetterOrDigit() && n.text[p.length].isLetterOrDigit()) return null
        val end = n.ends[p.length - 1]
        return name.substring(end).trimStart { isSeparator(it) }.trimEnd()
    }

    private val HASH_NUMBER = Regex("""#\s*0*(\d{1,6})""")
    private val WORD_NUMBER = Regex(
        """(?i)(?<![\p{L}\p{N}])(?:tomo|vol(?:umen|ume)?\.?|n[º°o]\.?|n[uú]m(?:ero)?\.?|issue|ep(?:isodio)?\.?|cap(?:[ií]tulo)?\.?|libro|parte|part)\s*0*(\d{1,6})""",
    )
    private val NUMBER = Regex("""(?<![\p{L}\p{N}])\d{1,6}(?![\p{L}\p{N}])""")
    private val GROUPS = Regex("""[(\[][^()\[\]]*[)\]]""")

    /**
     * Número del ejemplar a partir del nombre del fichero (para «números que faltan»), mirando el nombre sin el
     * prefijo del estante y, primero, lo que no está entre paréntesis. Por orden: «#04»; «Tomo 54», «Vol. 3», «Nº 7»…;
     * el primer número suelto que no sea un año (1900-2099). null si no hay (un año solo, como el de «Título (1999)»,
     * no es un número de ejemplar).
     */
    fun issueNumber(fileName: String, folderName: String): Int? {
        val name = smartName(fileName, folderName)
        // Primero fuera de paréntesis («Tomo 54 (#505-514)» es el 54); si ahí no hay nada, el nombre entero.
        val outside = name.replace(GROUPS, " ")
        return numberIn(outside) ?: numberIn(name)
    }

    private fun numberIn(text: String): Int? {
        HASH_NUMBER.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        WORD_NUMBER.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        val numbers = NUMBER.findAll(text).map { it.value }.toList()
        val nonYear = numbers.firstOrNull { !(it.length == 4 && it.toInt() in 1900..2099) }
        return nonYear?.toIntOrNull()
    }
}
