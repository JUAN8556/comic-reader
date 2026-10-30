package mx.jano.comics.ui

import mx.jano.comics.library.LibraryIndex
import mx.jano.comics.ui.settings.SettingsChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingAndStartupTest {
    @Test fun fallbackNaturalOrder() {
        val sorted = listOf("Tomo 10", "tomo 2", "Tomo 1", "Ábaco", "abaco 3", "Tomo 02b").sortedWith(FallbackNatural)
        assertEquals(listOf("Ábaco", "abaco 3", "Tomo 1", "tomo 2", "Tomo 02b", "Tomo 10"), sorted)
        assertTrue(FallbackNatural.compare("a", "a 1") < 0)
        assertEquals(0, FallbackNatural.compare("x", "x"))
    }

    @Test fun stripExtensionOnlyRemovesShortExtensions() {
        assertEquals("Uncanny X-Men 174", UiNaming.stripExtension("Uncanny X-Men 174.cbr"))
        assertEquals("Vol. 2", UiNaming.stripExtension("Vol. 2"))
        assertEquals(".indice", UiNaming.stripExtension(".indice"))
        assertEquals("Berserk v1", UiNaming.stripExtension("Berserk v1.pdf"))
    }

    @Test fun smartNameNeverKeepsTheExtensionAndNeverThrows() {
        val name = UiNaming.smartName("Uncanny X-Men 174, October 1983.cbr", "Uncanny X-Men")
        assertFalse(name.endsWith(".cbr"))
        assertTrue(name.isNotBlank())
    }

    @Test fun textNormalization() {
        assertEquals("x men dias del futuro pasado", TextNorm.normalize("X-Men: Días del futuro, ¡pasado!"))
        assertEquals(listOf("nino", "12"), TextNorm.tokens("  Niño   #12 "))
        assertEquals(emptyList<String>(), TextNorm.tokens(" — "))
    }

    @Test fun startupRescansWhenTheIndexIsMissingForeignOrOld() {
        val now = 10 * Startup.RESCAN_AFTER_MS
        fun idx(root: String, at: Long) = LibraryIndex(root, "Cómics", at, emptyList(), emptyList())
        assertTrue(Startup.needsRescan(null, "R", now))
        assertTrue(Startup.needsRescan(idx("OTRA", now), "R", now))
        assertTrue(Startup.needsRescan(idx("R", now - Startup.RESCAN_AFTER_MS - 1), "R", now))
        assertFalse(Startup.needsRescan(idx("R", now - 60_000), "R", now))
    }

    @Test fun settingsChoicesKeepAnUnusualSavedValueVisible() {
        assertEquals(listOf(10, 20, 40, 100), SettingsChoices.withCurrent(SettingsChoices.CACHE_LIMITS_GB, 20))
        assertEquals(listOf(10, 20, 30, 40, 100), SettingsChoices.withCurrent(SettingsChoices.CACHE_LIMITS_GB, 30))
    }
}
