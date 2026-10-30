package mx.jano.comics.reader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import mx.jano.comics.settings.FitMode
import mx.jano.comics.settings.PageLayout
import kotlin.math.roundToInt

/** El botón «Manga» de la superposición: lo elegido para ESTE libro y lo que heredaría. */
internal class MangaMenuModel(
    val choice: MangaChoice,
    val inherited: Boolean,
    val onChoose: (MangaChoice) -> Unit,
)

internal fun fitLabel(fit: FitMode): String = when (fit) {
    FitMode.WIDTH -> ReaderTexts.FIT_WIDTH
    FitMode.HEIGHT -> ReaderTexts.FIT_HEIGHT
    FitMode.BOTH -> ReaderTexts.FIT_BOTH
}

internal fun layoutLabel(layout: PageLayout): String = when (layout) {
    PageLayout.SINGLE -> ReaderTexts.LAYOUT_SINGLE
    PageLayout.DOUBLE_AUTO -> ReaderTexts.LAYOUT_DOUBLE
    PageLayout.VERTICAL -> ReaderTexts.LAYOUT_VERTICAL
}

/**
 * Superposición (toque en el tercio central): arriba Cerrar, el nombre del libro y los botones Ajuste, Páginas y
 * Manga; abajo «5 / 36» (o «5-6 / 36» en un pliego doble), el número de pliego y la barra para saltar, que va en
 * páginas. Las dos bandas se tragan los toques; el hueco del centro los deja pasar (centro = ocultar).
 * Cada estado se dice con texto (y la elección de un menú con un botón de radio), nunca solo con color.
 */
@Composable
internal fun ReaderOverlay(
    title: String,
    position: ReaderPosition,
    layout: EffectiveLayout,
    fit: FitMode,
    pageLayout: PageLayout,
    rtl: Boolean,
    manga: MangaMenuModel?,
    showBackHint: Boolean,
    palette: ReaderPalette,
    onClose: () -> Unit,
    onFit: (FitMode) -> Unit,
    onLayout: (PageLayout) -> Unit,
    onSeek: (Int) -> Unit,
) {
    val band = Modifier
        .fillMaxWidth()
        .background(palette.band)
        .pointerInput(Unit) { detectTapGestures { } }
    Column(Modifier.fillMaxSize()) {
        Column(
            modifier = band
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) {
                    Text(ReaderTexts.CLOSE, color = palette.onBand, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    color = palette.onBand,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fitOptions = listOf(FitMode.WIDTH, FitMode.HEIGHT, FitMode.BOTH)
                val fitApplies = FitResolver.fitApplies(layout)
                MenuButton(
                    label = ReaderTexts.fitButton(if (fitApplies) fitLabel(fit) else ReaderTexts.FIT_WIDTH),
                    palette = palette,
                    options = fitOptions.map { fitLabel(it) },
                    selected = fitOptions.indexOf(fit),
                    note = if (fitApplies) null else ReaderTexts.FIT_VERTICAL_NOTE,
                    enabled = fitApplies,
                    onSelect = { onFit(fitOptions[it]) },
                )
                val layoutOptions = listOf(PageLayout.SINGLE, PageLayout.DOUBLE_AUTO, PageLayout.VERTICAL)
                MenuButton(
                    label = ReaderTexts.layoutButton(layoutLabel(pageLayout)),
                    palette = palette,
                    options = layoutOptions.map { layoutLabel(it) },
                    selected = layoutOptions.indexOf(pageLayout),
                    note = if (pageLayout == PageLayout.DOUBLE_AUTO && layout == EffectiveLayout.SINGLE) ReaderTexts.LAYOUT_DOUBLE_NOTE else null,
                    onSelect = { onLayout(layoutOptions[it]) },
                )
                if (manga != null) {
                    val choices = listOf(MangaChoice.YES, MangaChoice.NO, MangaChoice.INHERIT)
                    MenuButton(
                        label = ReaderTexts.mangaButton(rtl),
                        palette = palette,
                        options = listOf(ReaderTexts.MANGA_YES, ReaderTexts.MANGA_NO, ReaderTexts.mangaInherit(manga.inherited)),
                        selected = choices.indexOf(manga.choice),
                        onSelect = { manga.onChoose(choices[it]) },
                    )
                }
            }
            if (showBackHint) {
                Text(
                    ReaderTexts.BACK_AGAIN,
                    color = palette.onBand,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 2.dp),
                )
            }
        }
        Spacer(Modifier.weight(1f))
        BottomBand(position, layout, rtl, palette, band, onSeek)
    }
}

@Composable
private fun BottomBand(
    position: ReaderPosition,
    layout: EffectiveLayout,
    rtl: Boolean,
    palette: ReaderPalette,
    band: Modifier,
    onSeek: (Int) -> Unit,
) {
    val pageCount = position.pageCount
    var dragging by remember { mutableStateOf<Float?>(null) }
    val label = dragging?.let { d ->
        val p = d.roundToInt().coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        ReaderFormat.pagesLabel(p, p, pageCount)
    } ?: ReaderFormat.pagesLabel(position.page, position.lastPage, pageCount)
    Column(
        modifier = band
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                color = palette.onBand,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics {
                    contentDescription = ReaderTexts.pageOf(position.page + 1, pageCount)
                },
            )
            if (layout == EffectiveLayout.DOUBLE && position.spreadCount > 0) {
                Spacer(Modifier.width(12.dp))
                Text(
                    ReaderTexts.spreadOf(position.spread + 1, position.spreadCount),
                    color = palette.secondary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                if (rtl) ReaderTexts.DIRECTION_RTL else ReaderTexts.DIRECTION_LTR,
                color = palette.secondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (pageCount > 1) {
            // En manga la barra también va de derecha a izquierda: la página 1 queda a la derecha.
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Slider(
                    value = dragging ?: position.page.toFloat(),
                    onValueChange = { dragging = it },
                    onValueChangeFinished = {
                        val target = (dragging ?: return@Slider).roundToInt().coerceIn(0, pageCount - 1)
                        dragging = null
                        onSeek(target)
                    },
                    valueRange = 0f..(pageCount - 1).toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.outline,
                    ),
                )
            }
        }
    }
}

/** Botón con menú desplegable de opciones excluyentes (la elegida con radio marcado y anunciada como «elegido»). */
@Composable
private fun MenuButton(
    label: String,
    palette: ReaderPalette,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    note: String? = null,
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { open = true },
            border = BorderStroke(if (palette.highContrast) 2.dp else 1.dp, palette.outline),
        ) {
            Text(label, color = palette.onBand)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = if (palette.highContrast) Color.Black else Color(0xFF1C1C1C),
            border = if (palette.highContrast) BorderStroke(2.dp, Color.White) else null,
        ) {
            options.forEachIndexed { i, option ->
                val isSelected = i == selected
                DropdownMenuItem(
                    text = { Text(option, color = palette.onBand) },
                    onClick = {
                        open = false
                        onSelect(i)
                    },
                    enabled = enabled,
                    leadingIcon = {
                        RadioButton(
                            selected = isSelected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = palette.accent,
                                unselectedColor = palette.onBand,
                                disabledSelectedColor = palette.secondary,
                                disabledUnselectedColor = palette.secondary,
                            ),
                        )
                    },
                    modifier = if (isSelected) Modifier.semantics { contentDescription = "$option, ${ReaderTexts.SELECTED}" } else Modifier,
                )
            }
            if (note != null) {
                Text(
                    note,
                    color = palette.secondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}
