package mx.jano.comics

import mx.jano.comics.auth.TokenProvider
import mx.jano.comics.cache.CacheManager
import mx.jano.comics.cache.PinManager
import mx.jano.comics.cache.TransferQueue
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.library.CoverStore
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.metadata.MetadataRepository
import mx.jano.comics.settings.SettingsStore

/**
 * CONTRATO COMÚN del MVP: el grafo de dependencias, a mano (sin Hilt). Lo construye ComicsApp (frente «ui-library»)
 * con las factorías de cada paquete; las pantallas lo reciben por parámetro o por un CompositionLocal.
 */
interface AppGraph {
    val tokens: TokenProvider
    val drive: DriveApi
    val appData: AppDataStore
    val settings: SettingsStore
    val library: LibraryRepository
    val progress: ProgressStore
    val covers: CoverStore
    val metadata: MetadataRepository
    val cache: CacheManager
    val pins: PinManager
    val transfers: TransferQueue
}
