package mx.jano.comics.reader

import androidx.compose.runtime.Composable
import mx.jano.comics.AppGraph
import mx.jano.comics.library.Book

/**
 * CONTRATO COMÚN del MVP. Pantalla completa de lectura de un libro de la biblioteca: asegura la copia local
 * (progreso de descarga visible), abre el archivo, aplica ajustes (ajuste, pliegos, vertical, RTL efectivo),
 * guarda el progreso con debounce, al terminar ofrece «Siguiente: N+1» (prefetch vía graph.transfers) y
 * excluye los bordes del gesto «atrás» del sistema. La rellena el frente «ui-reader».
 */
@Composable
fun ReaderRoute(
    graph: AppGraph,
    book: Book,
    onOpenBook: (Book) -> Unit,   // para saltar al siguiente número
    onClose: () -> Unit,
) {
    TODO("mvp/ui-reader")
}
