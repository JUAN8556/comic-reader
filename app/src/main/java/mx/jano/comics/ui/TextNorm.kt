package mx.jano.comics.ui

import java.text.Normalizer
import java.util.Locale

/** Normalización de texto para comparar y buscar: sin acentos, en minúsculas y con la puntuación como espacio. */
object TextNorm {
    private val marks = Regex("\\p{Mn}+")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    /** «Cómics Ñ» → «comics n». Conserva la puntuación. */
    fun fold(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** «X-Men: Días del futuro» → «x men dias del futuro». */
    fun normalize(s: String): String = nonWord.replace(fold(s), " ").trim()

    /** Palabras de una consulta, ya normalizadas. */
    fun tokens(query: String): List<String> = normalize(query).split(' ').filter { it.isNotEmpty() }
}
