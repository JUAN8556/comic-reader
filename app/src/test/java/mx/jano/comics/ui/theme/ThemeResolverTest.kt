package mx.jano.comics.ui.theme

import mx.jano.comics.settings.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolverTest {
    private val ink = mapOf(
        "secure/nxtpaper_ink_style_state" to "2",
        "system/color_mode_value" to "1",
        "system/adv_color_mode_value" to "10",
    )

    @Test fun fixedModesIgnoreTheTablet() {
        assertFalse(ThemeResolver.useHighContrast(ThemeMode.COVER_DARK, ink, ink))
        assertTrue(ThemeResolver.useHighContrast(ThemeMode.HIGH_CONTRAST, emptyMap(), null))
    }

    @Test fun autoNeedsASavedInkPaperValue() {
        assertFalse(ThemeResolver.useHighContrast(ThemeMode.AUTO_NXTPAPER, ink, null))
        assertFalse(ThemeResolver.useHighContrast(ThemeMode.AUTO_NXTPAPER, ink, emptyMap()))
        // Si al guardar no se pudo leer ninguna clave, nunca coincide (si no, sería alto contraste para siempre).
        val unreadable = ink.mapValues { null }
        assertFalse(ThemeResolver.useHighContrast(ThemeMode.AUTO_NXTPAPER, unreadable, unreadable))
    }

    @Test fun autoAppliesHighContrastOnlyWhenEverySavedValueMatches() {
        assertTrue(ThemeResolver.useHighContrast(ThemeMode.AUTO_NXTPAPER, ink, ink))
        val normal = ink + ("secure/nxtpaper_ink_style_state" to "0")
        assertFalse(ThemeResolver.useHighContrast(ThemeMode.AUTO_NXTPAPER, normal, ink))
        val colorChanged = ink + ("system/color_mode_value" to "3")
        assertFalse(ThemeResolver.isInkPaper(colorChanged, ink))
    }

    @Test fun describeListsTheThreeKeys() {
        assertEquals(
            "secure/nxtpaper_ink_style_state=2 · system/color_mode_value=1 · system/adv_color_mode_value=null",
            NxtPaperKeys.describe(ink - "system/adv_color_mode_value"),
        )
    }
}
