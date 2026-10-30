package mx.jano.comics.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

class FormatTest {
    private val mib = 1024L * 1024
    private val gib = mib * 1024

    @Test fun bytesInSpanish() {
        assertEquals("0 KB", UiFormat.bytes(0))
        assertEquals("1 KB", UiFormat.bytes(1))
        assertEquals("512 KB", UiFormat.bytes(512 * 1024))
        assertEquals("3,5 MB", UiFormat.bytes(3 * mib + mib / 2))
        assertEquals("120 MB", UiFormat.bytes(120 * mib))
        assertEquals("3,2 GB", UiFormat.bytes((3.2 * gib).toLong()))
        assertEquals("20,0 GB", UiFormat.bytes(20 * gib))
        assertEquals("150 GB", UiFormat.bytes(150 * gib))
        assertEquals("?", UiFormat.bytes(-1))
    }

    @Test fun comicsCountsWithSingularAndThousands() {
        assertEquals("1 cómic", UiFormat.comics(1))
        assertEquals("0 cómics", UiFormat.comics(0))
        assertEquals("30 cómics", UiFormat.comics(30))
        assertEquals("12.345 cómics", UiFormat.comics(12345))
    }

    @Test fun numberRangesCompressRuns() {
        assertEquals("1–3, 5, 7–9", UiFormat.numberRanges(listOf(9, 1, 2, 3, 5, 7, 8)))
        assertEquals("4", UiFormat.numberRanges(listOf(4)))
        assertEquals("4, 5", UiFormat.numberRanges(listOf(5, 4, 4)))
        assertEquals("", UiFormat.numberRanges(emptyList()))
        assertEquals("1, 3, 10–12", UiFormat.numberRanges(listOf(1, 3, 10, 11, 12)))
    }

    @Test fun relativeTimes() {
        val now = 1_000_000_000_000L
        val min = 60_000L
        assertEquals("hace un momento", UiFormat.relativeTime(now - 20_000, now))
        assertEquals("hace 5 min", UiFormat.relativeTime(now - 5 * min, now))
        assertEquals("hace 2 h", UiFormat.relativeTime(now - 125 * min, now))
        assertEquals("ayer", UiFormat.relativeTime(now - 30 * 60 * min, now))
        assertEquals("hace 3 días", UiFormat.relativeTime(now - 3 * 24 * 60 * min, now))
        assertEquals("hace un momento", UiFormat.relativeTime(now + 5 * min, now))
        val longAgo = UiFormat.relativeTime(now - 30L * 24 * 60 * min, now, ZoneOffset.UTC)
        assertEquals(UiFormat.date(now - 30L * 24 * 60 * min, ZoneOffset.UTC), longAgo)
    }

    @Test fun datesAndPercent() {
        assertEquals("30 oct 2026, 22:15", UiFormat.dateTime(UiFormat.parseIso("2026-10-30T22:15:03.120Z")!!, ZoneOffset.UTC).replace(".", ""))
        assertNull(UiFormat.parseIso("ayer"))
        assertNull(UiFormat.parseIso(null))
        assertEquals(50, UiFormat.percent(5, 10))
        assertEquals(100, UiFormat.percent(15, 10))
        assertNull(UiFormat.percent(5, null))
        assertNull(UiFormat.percent(5, 0))
    }
}
