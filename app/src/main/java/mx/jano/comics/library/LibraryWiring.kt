package mx.jano.comics.library

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.drive.AppDataStores
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.from
import mx.jano.comics.metadata.MetadataWiring
import mx.jano.comics.settings.SettingsStore
import java.io.File
import java.text.Normalizer

/** Cómo construyen [LibraryFactories] sus piezas en la app (Context, ficheros, ámbito, trazas). */
internal object LibraryWiring {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val log: (String) -> Unit = { android.util.Log.i("Biblioteca", it) }

    fun repository(context: Context, drive: DriveApi, settings: SettingsStore): DriveLibraryRepository =
        DriveLibraryRepository(
            indexFile = File(context.applicationContext.filesDir, "index.json"),
            lister = DriveAccess.listerFor(drive),
            resolveRoot = { resolveRoot(drive, settings) },
            configuredRootId = { settings.app.value.rootFolderId },
            scope = scope,
            log = log,
        )

    /**
     * La raíz de los ajustes; si no hay ninguna elegida, busca «Cómics» (o el nombre guardado) en «Mi unidad», en NFC y
     * en NFD (la Mac sube NFD), y la deja guardada en los ajustes.
     */
    internal suspend fun resolveRoot(drive: DriveApi, settings: SettingsStore): RootRef {
        val app = settings.app.value
        val name = app.rootFolderName ?: LibraryStrings.DEFAULT_ROOT_NAME
        app.rootFolderId?.let { return RootRef(it, name) }
        val nfc = Normalizer.normalize(name, Normalizer.Form.NFC)
        val nfd = Normalizer.normalize(name, Normalizer.Form.NFD)
        val found = drive.findFolder(nfc) ?: (if (nfd != nfc) drive.findFolder(nfd) else null)
            ?: throw RootMissingException(LibraryStrings.rootNotFound(nfc))
        settings.updateApp { it.copy(rootFolderId = found.id, rootFolderName = found.name) }
        return RootRef(found.id, found.name)
    }

    @Volatile private var progress: JsonProgressStore? = null

    /** Una por proceso (files/progress.json): dos almacenes sobre el mismo fichero se pisarían. */
    fun progressStore(context: Context, appData: AppDataStore?): JsonProgressStore =
        progress ?: synchronized(this) {
            progress ?: JsonProgressStore(
                file = File(context.applicationContext.filesDir, JsonProgressStore.FILE_NAME),
                appData = appData,
                scope = scope,
                log = log,
            ).also { progress = it }
        }

    fun coverStore(context: Context, drive: DriveApi, library: LibraryRepository): DriveCoverStore {
        val app = context.applicationContext
        return DriveCoverStore(
            coversDir = File(app.filesDir, "covers"),
            workDir = File(app.cacheDir, "portadas"),
            ranges = DriveAccess.rangeReaderFor(drive),
            indice = MetadataWiring.indiceSync(app, drive, library),
            encoder = BitmapCoverEncoder(),
            log = log,
        )
    }

    fun appDataFor(drive: DriveApi): AppDataStore? = AppDataStores.from(drive)
}

/**
 * Igual que [LibraryFactories.progressStore] pero con el [AppDataStore] del grafo (`graph.appData`) en vez de
 * deducirlo del cliente de Drive.
 */
fun LibraryFactories.progressStore(context: Context, appData: AppDataStore): ProgressStore =
    LibraryWiring.progressStore(context, appData)
