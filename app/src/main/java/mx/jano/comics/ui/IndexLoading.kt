package mx.jano.comics.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mx.jano.comics.library.DriveLibraryRepository
import mx.jano.comics.library.LibraryRepository

/**
 * ¿Sigue leyéndose index.json de disco? En un arranque en frío el índice tarda un poco en llegar, y mientras tanto
 * `index == null` no quiere decir «sin escanear» ni «ese cómic ya no está». Solo lo sabe el repositorio de Drive
 * ([DriveLibraryRepository.diskLoaded]); con cualquier otro (los dobles de las vistas previas) se da por cargado.
 */
@Composable
internal fun rememberIndexLoading(library: LibraryRepository): Boolean {
    val flow = remember(library) { (library as? DriveLibraryRepository)?.diskLoaded ?: AlwaysLoaded }
    val loaded by flow.collectAsStateWithLifecycle()
    return !loaded
}

private val AlwaysLoaded: StateFlow<Boolean> = MutableStateFlow(true)
