package mx.jano.comics

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mx.jano.comics.auth.AuthProviders
import mx.jano.comics.auth.TokenProvider
import mx.jano.comics.cache.CacheFactories
import mx.jano.comics.cache.CacheManager
import mx.jano.comics.cache.PinManager
import mx.jano.comics.cache.TransferQueue
import mx.jano.comics.cache.cacheManager
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.drive.AppDataStores
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveApis
import mx.jano.comics.drive.from
import mx.jano.comics.library.CoverStore
import mx.jano.comics.library.LibraryFactories
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.library.progressStore
import mx.jano.comics.metadata.MetadataFactories
import mx.jano.comics.metadata.MetadataRepository
import mx.jano.comics.settings.SettingsFactories
import mx.jano.comics.settings.SettingsStore
import mx.jano.comics.ui.UiEnv
import mx.jano.comics.ui.UiPrefsStore
import mx.jano.comics.ui.theme.NxtPaperModeWatcher
import java.io.File

/**
 * La aplicación: dueña del grafo de dependencias (una instancia por proceso, cada pieza perezosa) y del ámbito de
 * corrutinas de la app. MainActivity lo expone a la interfaz por LocalAppGraph y LocalUiEnv.
 *
 * Es además el propio [AppGraph] (delegando en [graph]): el trabajo de fijados (PinJobService) y el worker del N+1
 * los crea el sistema, a veces con el proceso recién arrancado y sin ninguna pantalla; buscan sus piezas con
 * `applicationContext as? AppGraph` (CacheRuntime).
 */
class ComicsApp : Application(), AppGraph {
    /** Ámbito de la aplicación: lo que el usuario pide (fijar, marcar, copiar) no se corta al cambiar de pantalla. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val graph: AppGraph by lazy { DefaultAppGraph(this) }

    override val tokens: TokenProvider get() = graph.tokens
    override val drive: DriveApi get() = graph.drive
    override val appData: AppDataStore get() = graph.appData
    override val settings: SettingsStore get() = graph.settings
    override val library: LibraryRepository get() = graph.library
    override val progress: ProgressStore get() = graph.progress
    override val covers: CoverStore get() = graph.covers
    override val metadata: MetadataRepository get() = graph.metadata
    override val cache: CacheManager get() = graph.cache
    override val pins: PinManager get() = graph.pins
    override val transfers: TransferQueue get() = graph.transfers

    val ui: UiEnv by lazy {
        UiEnv(
            graph = graph,
            prefs = UiPrefsStore(File(filesDir, "ui-prefs.json")),
            inkMode = NxtPaperModeWatcher(this),
            scope = appScope,
            versionName = BuildConfig.VERSION_NAME,
        )
    }
}

/** El grafo con las implementaciones reales; cada pieza se construye la primera vez que alguien la pide. */
class DefaultAppGraph(context: Context) : AppGraph {
    private val app: Context = context.applicationContext

    override val tokens: TokenProvider by lazy { AuthProviders.playServices(app) }
    override val drive: DriveApi by lazy { DriveApis.rest(tokens) }
    /** El mismo cliente de Drive (token, 401, backoff) para appDataFolder; uno propio solo si no fuera el REST. */
    override val appData: AppDataStore by lazy { AppDataStores.from(drive) ?: AppDataStores.rest(tokens) }
    override val settings: SettingsStore by lazy { SettingsFactories.store(app) }
    override val library: LibraryRepository by lazy { LibraryFactories.repository(app, drive, settings) }
    /** Con el appData del grafo: la copia en Drive y la fecha de la última copia (Ajustes) miran el mismo sitio. */
    override val progress: ProgressStore by lazy { LibraryFactories.progressStore(app, appData) }
    override val covers: CoverStore by lazy { LibraryFactories.coverStore(app, drive, library) }
    override val metadata: MetadataRepository by lazy { MetadataFactories.repository(app, drive, library) }
    /** Con el progreso: el desalojo va terminados → sin empezar → leyéndose (sin él sería LRU puro). */
    override val cache: CacheManager by lazy { CacheFactories.cacheManager(app, drive, settings, library, progress) }
    override val pins: PinManager by lazy { CacheFactories.pinManager(app, cache, library) }
    override val transfers: TransferQueue by lazy { CacheFactories.transferQueue(app, cache, settings) }
}
