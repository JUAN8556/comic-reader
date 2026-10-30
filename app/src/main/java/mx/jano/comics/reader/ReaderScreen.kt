package mx.jano.comics.reader

import androidx.compose.runtime.Composable
import mx.jano.comics.archive.ComicArchive
import java.io.File

/**
 * CONTRATO COMÚN. Lector de un cómic ya abierto: HorizontalPager (reverseLayout si [rtl]) + zoom con Telephoto,
 * páginas extraídas a [pageCacheDir] y cargadas con Coil desde fichero (teselado para escaneos grandes).
 * Inmersivo (barras del sistema ocultas), FLAG_KEEP_SCREEN_ON, atrás predictivo → [onClose].
 */
@Composable
fun ReaderScreen(
    archive: ComicArchive,
    pageCacheDir: File,
    startPage: Int = 0,
    rtl: Boolean = false,
    onClose: () -> Unit,
) {
    TODO("sonda/reader")
}
