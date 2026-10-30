package mx.jano.comics.probe

import java.text.Normalizer
import java.util.Locale

/** Lógica pura de la sonda (sin Android) para probarla en la JVM. */
object ProbeFormat {
    /** Coma decimal, como escribe Jano. */
    private val ES: Locale = Locale.forLanguageTag("es-ES")

    const val MAX_SAMPLE_BYTES: Long = 80L * 1024 * 1024
    private val SAMPLE_EXTENSIONS = listOf("cbr", "cbz", "pdf")
    private val INK_HINTS = listOf("ink", "paper", "tct", "nxt")

    fun mb(bytes: Long): String = String.format(ES, "%.1f MB", bytes / 1_048_576.0)

    fun gb(bytes: Long): String = String.format(ES, "%.2f GB", bytes / 1_073_741_824.0)

    /** MB/s con un decimal; «—» si aún no hay tiempo medido. */
    fun mbPerSecond(bytes: Long, millis: Long): String =
        if (millis <= 0L) "— MB/s" else String.format(ES, "%.1f MB/s", bytes / 1_048_576.0 / (millis / 1000.0))

    fun seconds(millis: Long): String = String.format(ES, "%.1f s", millis / 1000.0)

    fun days(millis: Long): String = String.format(ES, "%.2f días", millis / 86_400_000.0)

    /** Muestra válida: fichero (no carpeta) con tamaño conocido ≤ 80 MB y extensión .cbr/.cbz/.pdf. */
    fun isSampleCandidate(name: String, isFolder: Boolean, size: Long?): Boolean {
        if (isFolder || size == null || size <= 0L || size > MAX_SAMPLE_BYTES) return false
        return sampleExtension(name) != null
    }

    /** «cbr», «cbz» o «pdf» según el final del nombre (sin distinguir mayúsculas); null si no es ninguno. */
    fun sampleExtension(name: String): String? {
        val ext = name.trim().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return ext.takeIf { it in SAMPLE_EXTENSIONS }
    }

    /** Clave para comparar nombres de carpeta: NFC, sin espacios a los lados, sin mayúsculas. */
    fun folderKey(name: String): String =
        Normalizer.normalize(name, Normalizer.Form.NFC).trim().lowercase(Locale.ROOT)

    /** ¿El nombre de un ajuste del sistema huele a modo de papel de TCL? */
    fun looksLikeInkSetting(name: String): Boolean {
        val n = name.lowercase(Locale.ROOT)
        return INK_HINTS.any { it in n }
    }

    /** Settings.Secure navigation_mode: 0 = tres botones, 1 = dos botones, 2 = gestos. */
    fun navigationModeLabel(value: String?): String = when (value?.trim()) {
        null -> "sin valor"
        "0" -> "0 (tres botones)"
        "1" -> "1 (dos botones)"
        "2" -> "2 (gestos)"
        else -> "$value (desconocido)"
    }

    /** Índices que mide «Abrir archivo»: la primera, la del medio y la última, sin repetir. */
    fun probePages(pageCount: Int): List<Int> =
        if (pageCount <= 0) emptyList() else listOf(0, pageCount / 2, pageCount - 1).distinct()
}
