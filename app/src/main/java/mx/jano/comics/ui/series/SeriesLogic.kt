package mx.jano.comics.ui.series

import mx.jano.comics.metadata.SeriesMeta
import mx.jano.comics.ui.UiStrings

/** Una fila «etiqueta: valor» de la ficha. */
data class SeriesRow(val label: String, val value: String)

object SeriesLogic {
    fun isLowConfidence(meta: SeriesMeta): Boolean = meta.confianza?.trim()?.lowercase() == "baja"

    /** Título que se muestra: el de la ficha, o el nombre de la carpeta. */
    fun title(meta: SeriesMeta?, folderName: String): String = meta?.titulo?.takeIf { it.isNotBlank() } ?: folderName

    /** Filas de la ficha con datos (las vacías no salen), en el orden en que se leen. */
    fun rows(meta: SeriesMeta): List<SeriesRow> {
        val out = mutableListOf<SeriesRow>()
        fun add(label: String, value: String?) {
            val v = value?.trim()
            if (!v.isNullOrEmpty()) out += SeriesRow(label, v)
        }
        fun addList(label: String, values: List<String>) = add(label, values.filter { it.isNotBlank() }.joinToString(", "))

        if (meta.titulo_original != null && meta.titulo_original != meta.titulo) add(UiStrings.SERIES_ORIGINAL, meta.titulo_original)
        add(UiStrings.SERIES_TYPE, meta.tipo)
        add(UiStrings.SERIES_CATEGORY, meta.categoria)
        add(UiStrings.SERIES_COUNTRY, meta.pais)
        addList(UiStrings.SERIES_SCRIPT, meta.guion)
        addList(UiStrings.SERIES_ART, meta.dibujo)
        add(UiStrings.SERIES_PUBLISHER_ORIGINAL, meta.editorial_original)
        add(UiStrings.SERIES_PUBLISHER_EDITION, meta.editorial_edicion)
        add(UiStrings.SERIES_LANGUAGE, meta.idioma_edicion)
        add(UiStrings.SERIES_YEARS, meta.anios)
        addList(UiStrings.SERIES_GENRES, meta.generos)
        add(UiStrings.SERIES_STATUS, meta.estado)
        add(UiStrings.SERIES_ISSUES, UiStrings.seriesIssues(meta.numeros_en_carpeta, meta.numeros_totales))
        return out
    }

    /** Las fuentes que son enlaces web (las demás se muestran como texto). */
    fun isLink(source: String): Boolean = source.startsWith("https://") || source.startsWith("http://")
}
