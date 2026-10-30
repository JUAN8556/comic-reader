package mx.jano.comics.metadata

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.settings.SettingsFactories
import java.io.File

/** Piezas compartidas por las factorías de metadata y de portadas (todo en el proceso de la app). */
internal object MetadataWiring {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val log: (String) -> Unit = { android.util.Log.i("Metadatos", it) }

    @Volatile private var sync: IndiceSync? = null

    /**
     * Uno por proceso: metadata y portadas escriben el mismo files/indice/estado.json y tienen que pasar por el
     * mismo cerrojo. El primero que lo pide fija el cliente de Drive y la biblioteca.
     */
    fun indiceSync(context: Context, drive: DriveApi, library: LibraryRepository): IndiceSync =
        sync ?: synchronized(this) {
            sync ?: run {
                val app = context.applicationContext
                IndiceSync(
                    dir = File(app.filesDir, "indice"),
                    drive = drive,
                    rootId = { library.index.value?.rootId ?: SettingsFactories.store(app).app.value.rootFolderId },
                    log = log,
                ).also { sync = it }
            }
        }
}
