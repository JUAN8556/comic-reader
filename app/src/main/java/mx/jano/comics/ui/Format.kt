package mx.jano.comics.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatos de la interfaz en español (coma decimal, «1.234»). Puros: con pruebas JVM. */
object UiFormat {
    private val es: Locale = Locale.forLanguageTag("es-ES")
    private const val KIB = 1024L
    private const val MIB = KIB * 1024
    private const val GIB = MIB * 1024

    fun count(n: Int): String = String.format(es, "%,d", n)

    fun comics(n: Int): String = if (n == 1) "1 cómic" else "${count(n)} cómics"

    /** Bytes en KB / MB / GB (base 1024, como el tope de caché en GB). */
    fun bytes(n: Long): String = when {
        n < 0 -> "?"
        n < MIB -> "${(n + KIB - 1) / KIB} KB"
        n < 10 * MIB -> String.format(es, "%.1f MB", n.toDouble() / MIB)
        n < GIB -> "${n / MIB} MB"
        n < 100 * GIB -> String.format(es, "%.1f GB", n.toDouble() / GIB)
        else -> "${n / GIB} GB"
    }

    fun seconds(ms: Long): String = if (ms < 10_000) String.format(es, "%.1f s", ms / 1000.0) else "${ms / 1000} s"

    /** Porcentaje entero 0-100, o null si no se sabe el total. */
    fun percent(done: Long, total: Long?): Int? {
        if (total == null || total <= 0) return null
        return ((done.coerceIn(0, total) * 100) / total).toInt()
    }

    /** «hace un momento», «hace 5 min», «hace 2 h», «ayer», «hace 3 días»; más allá, la fecha. */
    fun relativeTime(then: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = (now - then).coerceAtLeast(0)
        val minutes = diff / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "hace un momento"
            minutes < 60 -> "hace $minutes min"
            hours < 24 -> "hace $hours h"
            days < 2 -> "ayer"
            days < 7 -> "hace $days días"
            else -> date(then, zone)
        }
    }

    fun date(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy", es).withZone(zone).format(Instant.ofEpochMilli(millis))

    fun dateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", es).withZone(zone).format(Instant.ofEpochMilli(millis))

    /** Fecha ISO-8601 de Drive («2026-10-30T22:15:03.120Z») a epoch ms, o null si no se entiende. */
    fun parseIso(iso: String?): Long? = try {
        iso?.let { Instant.parse(it).toEpochMilli() }
    } catch (e: Exception) {
        null
    }

    /** [1, 2, 3, 5, 7, 8, 9] → «1–3, 5, 7–9». Ordena y quita repetidos. */
    fun numberRanges(numbers: List<Int>): String {
        val sorted = numbers.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted[0]
        var prev = start
        for (n in sorted.drop(1) + Int.MIN_VALUE) {
            if (n == prev + 1) {
                prev = n
                continue
            }
            parts += when {
                prev == start -> "$start"
                prev == start + 1 -> "$start, $prev"
                else -> "$start–$prev"
            }
            start = n
            prev = n
        }
        return parts.joinToString(", ")
    }
}
