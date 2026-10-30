package mx.jano.comics.metadata

/** Textos de interfaz del paquete metadata (español; pasan a strings.xml cuando llegue la i18n). */
internal object MetadataStrings {
    fun refreshFailed(detail: String?) = "No se pudo actualizar la ficha de las series desde Drive" + (detail?.let { ": $it" } ?: ".")
}
