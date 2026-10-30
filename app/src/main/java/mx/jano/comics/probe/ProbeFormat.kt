package mx.jano.comics.probe

import java.text.Normalizer
import java.util.Locale

/** Lógica pura de la sonda (sin Android) para probarla en la JVM. */
object ProbeFormat {
    /** Coma decimal, como escribe Jano. */
    private val ES: Locale = Locale.forLanguageTag("es-ES")

    const val MAX_SAMPLE_BYTES: Long = 80L * 1024 * 1024
    private val SAMPLE_EXTENSIONS = listOf("cbr", "cbz", "pdf")
    // Las cuatro primeras, de la investigación; el resto, de las claves que adb vio en la tablet el 29-oct.
    private val INK_HINTS = listOf("ink", "paper", "tct", "nxt", "color_mode", "eyeprotect", "reading_mode", "installed_theme")

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

    /**
     * Página que «Abrir archivo» extrae al final yendo HACIA ATRÁS (la anterior a la del medio): en un sólido debe
     * ser un acierto de la caché; sin caché, junrar reiniciaría desde la primera entrada. null con menos de 4 páginas.
     */
    fun backwardProbePage(pageCount: Int): Int? = if (pageCount >= 4) pageCount / 2 - 1 else null

    /**
     * Veredicto de la reanudación (incógnita 2). [requestedFrom] = bytes que había en disco; [firstByte] = el primer
     * progreso que dio la descarga, que es el byte desde el que escribió (206: el pedido; 200 o parcial descartado: 0;
     * −1 si no hubo progreso); [newBytes] = bytes bajados en esta sesión.
     */
    fun resumeVerdict(requestedFrom: Long, firstByte: Long, newBytes: Long): String = when {
        firstByte < 0 -> "reanudó: ¿? (sin progreso registrado: no se sabe desde qué byte sirvió el servidor)"
        firstByte == requestedFrom && newBytes == 0L ->
            "reanudó: sí, sin bajar nada (el servidor dijo con 416 que ya estaba completo)"
        firstByte == requestedFrom ->
            "reanudó: sí. Range honrado: el servidor sirvió desde el byte $requestedFrom (206 + Content-Range)"
        firstByte == 0L ->
            "reanudó: NO. La descarga empezó en el byte 0 (el motivo está en la línea «drive:» de arriba: " +
                "parcial de otra versión, 200 sin Range o Content-Range inesperado)"
        else -> "reanudó: NO como se esperaba (se pidió desde el byte $requestedFrom y se escribió desde el $firstByte)"
    }
}
