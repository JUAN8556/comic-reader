package mx.jano.comics.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import mx.jano.comics.archive.ComicArchive
import mx.jano.comics.settings.FitMode
import mx.jano.comics.settings.PageLayout
import mx.jano.comics.settings.ReaderSettings
import java.io.File

/**
 * CONTRATO COMÚN. Lector de un cómic ya abierto (lo usa la sonda, «Diagnóstico» en Ajustes): delega en el mismo
 * núcleo que [ReaderRoute] ([ReaderCore]) con ajustes locales que no se guardan y sin «Siguiente».
 *
 * Gestos: Telephoto consume el arrastre mientras hay zoom y lo cede al pager al llegar al borde de la imagen.
 * Toques: tercio central = superposición con «5 / 36», Ajuste, Páginas y barra para saltar; tercios laterales =
 * anterior/siguiente (invertidos en manga). Doble toque = zoom. El paso de página por toque es instantáneo
 * (scrollToPage): en la pantalla de papel una animación solo deja estelas. Inmersivo, FLAG_KEEP_SCREEN_ON y la
 * guarda de los bordes de UI-4 (ver [EdgeGuard]).
 */
@Composable
fun ReaderScreen(
    archive: ComicArchive,
    pageCacheDir: File,
    startPage: Int = 0,
    rtl: Boolean = false,
    onClose: () -> Unit,
) {
    var fit by rememberSaveable { mutableStateOf(FitMode.BOTH) }
    var layout by rememberSaveable { mutableStateOf(PageLayout.DOUBLE_AUTO) }
    val settings = ReaderSettings(fit = fit, layout = layout)
    ReaderCore(
        archive = archive,
        pageCacheDir = pageCacheDir,
        title = "",
        startPage = startPage,
        settings = settings,
        rtl = rtl,
        palette = ReaderPalette.Cover,
        onUpdateSettings = { transform ->
            val next = transform(settings)
            fit = next.fit
            layout = next.layout
        },
        manga = null,
        onPosition = {},
        endPage = null,
        onClose = onClose,
    )
}
