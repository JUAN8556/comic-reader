package mx.jano.comics.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import mx.jano.comics.library.Book
import mx.jano.comics.ui.LocalUiEnv
import mx.jano.comics.ui.theme.LocalComicsLook
import java.io.File

/** Proporción de una portada de cómic (ancho / alto). */
const val COVER_ASPECT = 0.68f

val CoverShape: Shape = RoundedCornerShape(4.dp)

/** Desplazamiento de cada portada de detrás en una pila (hacia arriba y a la derecha). Cabe en el hueco de la rejilla. */
const val STACK_SHIFT_DP = 4

/** El fichero de portada de un libro, o null mientras llega (o si no hay). */
@Composable
fun rememberCoverFile(book: Book?): File? {
    val loader = LocalUiEnv.current.covers
    val key = book?.contentKey
    val state = remember(key) { mutableStateOf(book?.let(loader::peek)) }
    LaunchedEffect(key) {
        if (state.value == null && book != null) state.value = loader.load(book)
    }
    return state.value
}

/**
 * Portada de un libro: tarjeta tipográfica con [title] mientras llega o si no hay; la imagen encima cuando está.
 * Bordes finos y sombra en Cover; en alto contraste, borde blanco y sin sombra.
 */
@Composable
fun CoverCard(
    book: Book?,
    title: String,
    modifier: Modifier = Modifier,
    elevated: Boolean = true,
) {
    val look = LocalComicsLook.current
    val file = rememberCoverFile(book)
    val base = if (elevated && look.decorations) modifier.shadow(6.dp, CoverShape, clip = false) else modifier
    Box(
        modifier = base
            .clip(CoverShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(if (look.highContrast) 1.dp else 0.5.dp, MaterialTheme.colorScheme.outline, CoverShape),
    ) {
        if (file == null) {
            TypographicCover(title)
        } else {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Tarjeta tipográfica: el nombre centrado, grande, para lo que no tiene portada. */
@Composable
fun BoxScope.TypographicCover(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        maxLines = 5,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.align(Alignment.Center).padding(10.dp),
    )
}

/**
 * Pila de 2-3 portadas desplazadas (las de los primeros libros de la carpeta), la primera delante y del tamaño de una
 * portada de libro (así carpetas y libros quedan alineados en la rejilla); las de detrás asoman hacia arriba a la
 * derecha, dentro del hueco entre celdas. Sin libros, una pila de tarjetas tipográficas con el nombre de la carpeta.
 * En alto contraste, sin giro ni sombras.
 */
@Composable
fun CoverStack(books: List<Book>, folderName: String, modifier: Modifier = Modifier) {
    val look = LocalComicsLook.current
    val layers = books.take(3)
    val count = if (layers.isEmpty()) 3 else maxOf(layers.size, 2)
    Box(modifier = modifier) {
        for (depth in (count - 1) downTo 0) {
            val book = layers.getOrNull(depth)
            val shift = (depth * STACK_SHIFT_DP).dp
            val angle = if (look.decorations) depth * 1.5f else 0f
            CoverCard(
                book = book,
                title = if (depth == 0) folderName else "",
                elevated = depth == 0,
                modifier = Modifier
                    .fillMaxSize()
                    .offset(x = shift, y = -shift)
                    .rotate(angle),
            )
        }
    }
}
