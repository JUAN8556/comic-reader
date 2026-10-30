package mx.jano.comics.reader

import mx.jano.comics.settings.FitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitResolverTest {
    private val landscapeScreen = PxSize(2200, 1440)
    private val portraitScreen = PxSize(1440, 2200)
    private val page = PxSize(1988, 3056)

    @Test
    fun cada_ajuste_da_su_escala_y_su_alineacion() {
        assertEquals(FitSpec(ScaleKind.FIT, AlignKind.CENTER), FitResolver.resolve(FitMode.BOTH, rtl = false))
        assertEquals(FitSpec(ScaleKind.FILL_WIDTH, AlignKind.TOP_CENTER), FitResolver.resolve(FitMode.WIDTH, rtl = false))
        assertEquals(FitSpec(ScaleKind.FILL_HEIGHT, AlignKind.CENTER_LEFT), FitResolver.resolve(FitMode.HEIGHT, rtl = false))
    }

    @Test
    fun en_manga_el_ajuste_al_alto_empieza_por_la_derecha() {
        assertEquals(AlignKind.CENTER_RIGHT, FitResolver.resolve(FitMode.HEIGHT, rtl = true).align)
        // Ancho y página entera no cambian con el sentido de lectura.
        assertEquals(FitResolver.resolve(FitMode.WIDTH, false), FitResolver.resolve(FitMode.WIDTH, true))
        assertEquals(FitResolver.resolve(FitMode.BOTH, false), FitResolver.resolve(FitMode.BOTH, true))
    }

    @Test
    fun si_no_desborda_se_centra() {
        // Vertical a lo ancho en pantalla horizontal: desborda hacia abajo → arriba.
        assertEquals(AlignKind.TOP_CENTER, FitResolver.resolve(FitMode.WIDTH, false, page, landscapeScreen).align)
        // Panorámica muy apaisada a lo ancho: queda más baja que la pantalla → centrada.
        assertEquals(AlignKind.CENTER, FitResolver.resolve(FitMode.WIDTH, false, PxSize(4000, 1000), landscapeScreen).align)
        // Al alto: una vertical en pantalla horizontal no desborda → centrada (no pegada a un lado).
        assertEquals(AlignKind.CENTER, FitResolver.resolve(FitMode.HEIGHT, true, page, landscapeScreen).align)
        // Al alto: una apaisada en pantalla vertical desborda a los lados → por donde se empieza a leer.
        assertEquals(AlignKind.CENTER_LEFT, FitResolver.resolve(FitMode.HEIGHT, false, PxSize(4000, 3000), portraitScreen).align)
        assertEquals(AlignKind.CENTER_RIGHT, FitResolver.resolve(FitMode.HEIGHT, true, PxSize(4000, 3000), portraitScreen).align)
        // La escala no cambia; sin tamaño se supone que desborda.
        assertEquals(ScaleKind.FILL_WIDTH, FitResolver.resolve(FitMode.WIDTH, false, PxSize(4000, 1000), landscapeScreen).scale)
        assertEquals(AlignKind.TOP_CENTER, FitResolver.resolve(FitMode.WIDTH, false, null, landscapeScreen).align)
        assertEquals(AlignKind.CENTER, FitResolver.resolve(FitMode.BOTH, false, page, landscapeScreen).align)
    }

    @Test
    fun el_ajuste_no_aplica_en_vertical_continuo() {
        assertTrue(FitResolver.fitApplies(EffectiveLayout.SINGLE))
        assertTrue(FitResolver.fitApplies(EffectiveLayout.DOUBLE))
        assertFalse(FitResolver.fitApplies(EffectiveLayout.VERTICAL))
    }

    @Test
    fun escala_base() {
        // Página vertical en pantalla horizontal: entera la limita el alto; a lo ancho desborda hacia abajo.
        assertEquals(1440f / 3056f, FitResolver.baseScale(ScaleKind.FIT, page, landscapeScreen), 1e-6f)
        assertEquals(2200f / 1988f, FitResolver.baseScale(ScaleKind.FILL_WIDTH, page, landscapeScreen), 1e-6f)
        assertEquals(1440f / 3056f, FitResolver.baseScale(ScaleKind.FILL_HEIGHT, page, landscapeScreen), 1e-6f)
        val shownWide = FitResolver.displayedSize(ScaleKind.FILL_WIDTH, page, landscapeScreen)
        assertEquals(2200, shownWide.width)
        assertTrue("a lo ancho desborda", shownWide.height > landscapeScreen.height)
        // En vertical la página (0,65) es un pelo más alargada que la pantalla (0,655): entera la limita el alto.
        assertEquals(2200f / 3056f, FitResolver.baseScale(ScaleKind.FIT, page, portraitScreen), 1e-6f)
        // Una página más ancha que la pantalla la limita el ancho.
        assertEquals(1440f / 2000f, FitResolver.baseScale(ScaleKind.FIT, PxSize(2000, 2000), portraitScreen), 1e-6f)
        // Sin medidas, 1.
        assertEquals(1f, FitResolver.baseScale(ScaleKind.FIT, PxSize(0, 0), portraitScreen), 0f)
    }

    @Test
    fun una_apaisada_al_alto_en_vertical_desborda_a_los_lados() {
        val wide = PxSize(4000, 3000)
        val shown = FitResolver.displayedSize(ScaleKind.FILL_HEIGHT, wide, portraitScreen)
        assertEquals(2200, shown.height)
        assertTrue(shown.width > portraitScreen.width)
    }

    @Test
    fun decodificar_al_tamano_de_pantalla_sin_pasar_del_original() {
        // Más pequeño que el original: el tamaño de pantalla.
        assertEquals(PxSize(936, 1440), FitResolver.decodeSize(PxSize(936, 1440), page, maxPixels = 10_000_000))
        // Más grande que el original: se queda en el original (no se amplía al decodificar).
        assertEquals(PxSize(1988, 3056), FitResolver.decodeSize(PxSize(3976, 6112), page, maxPixels = 100_000_000))
        // Tope de píxeles para una tira muy larga a lo ancho.
        val strip = FitResolver.decodeSize(PxSize(2200, 22000), PxSize(800, 8000), maxPixels = 2200L * 1440 * 2)
        assertTrue(strip.width.toLong() * strip.height <= 2200L * 1440 * 2 + 4000)
        assertEquals(0.1f, strip.width.toFloat() / strip.height, 0.002f)
    }

    @Test
    fun pliego_doble_a_la_misma_altura() {
        val g = FitResolver.spreadContent(PxSize(1000, 1500), PxSize(2000, 3000))
        assertEquals(3000f, g.height, 0f)
        assertEquals(2000f, g.leftWidth, 0.01f)
        assertEquals(2000f, g.rightWidth, 0.01f)
        assertEquals(4000f, g.width, 0.01f)
        // Dos páginas verticales en pantalla horizontal: cada mitad, la mitad del pliego en pantalla.
        val spread = FitResolver.spreadContent(page, page)
        val shown = FitResolver.displayedSize(ScaleKind.FIT, PxSize(spread.width.toInt(), spread.height.toInt()), landscapeScreen)
        assertEquals(1440, shown.height)
        val half = FitResolver.decodeSize(
            PxSize((spread.leftWidth * shown.height / spread.height).toInt(), shown.height),
            page,
            FitResolver.pixelBudget(landscapeScreen, halves = 2),
        )
        assertEquals(1440, half.height)
        assertTrue(half.width <= landscapeScreen.width / 2)
    }

    @Test
    fun presupuesto_de_pixeles() {
        assertEquals(2200L * 1440 * 2, FitResolver.pixelBudget(landscapeScreen, halves = 1))
        assertEquals(2200L * 1440, FitResolver.pixelBudget(landscapeScreen, halves = 2))
    }
}
