package mx.jano.comics.cache

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mx.jano.comics.AppGraph
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.library.LibraryRepository
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.settings.SettingsStore
import java.io.File

/**
 * Las piezas de la caché, una por proceso: el trabajo UIDT y el worker de WorkManager las buscan aquí (los crea el
 * sistema por reflexión, sin acceso al grafo). Las factorías de [CacheFactories] pasan por aquí: llamarlas dos
 * veces devuelve la misma instancia (dos gestores sobre la misma carpeta se pisarían).
 */
object CacheRuntime {
    private const val TAG = "Cache"

    /** Vive lo que el proceso. */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    var cache: DiskCacheManager? = null
        private set

    @Volatile
    var pins: PinController? = null
        private set

    @Volatile
    var transfers: WorkManagerTransferQueue? = null
        private set

    @Volatile
    private var visibility: AppVisibility? = null

    internal fun log(message: String) {
        Log.i(TAG, message)
    }

    fun cacheManager(
        context: Context,
        drive: DriveApi,
        settings: SettingsStore,
        library: LibraryRepository,
        progress: ProgressStore?,
    ): DiskCacheManager = synchronized(this) {
        cache?.let { existing ->
            if (progress != null) existing.progress = progress
            return existing
        }
        val app = context.applicationContext
        // getExternalFilesDir no pide permisos; si el volumen no está montado (raro en la tablet), interno.
        val books = app.getExternalFilesDir("books") ?: File(app.filesDir, "books")
        DiskCacheManager(
            booksDir = books,
            pagesRoot = File(app.cacheDir, "pages"),
            drive = drive,
            settings = settings,
            library = library,
            store = AtomicJsonFile(File(app.filesDir, "cache.json"), CacheSnapshot.serializer()),
            storage = AndroidStorageStats,
            clock = Clock.SYSTEM,
            scope = scope,
            io = Dispatchers.IO,
            adoptOrphans = true,
            log = ::log,
            progress = progress,
            // Con la factoría de 4 parámetros: el progreso se pide al grafo la primera vez que hace falta desalojar.
            progressLookup = { (app as? AppGraph)?.progress },
        ).also { cache = it }
    }

    fun pinManager(context: Context, cache: CacheManager, library: LibraryRepository): PinController = synchronized(this) {
        pins?.let { return it }
        val app = context.applicationContext
        val visible = visibility(app)
        val notifications = PinNotifications(app)
        val controller = PinController(
            cache = cache,
            store = AtomicJsonFile(File(app.filesDir, "pins.json"), PinSnapshot.serializer()),
            scheduler = JobPinScheduler(app),
            settings = (cache as? DiskCacheManager)?.settings,
            library = library,
            scope = scope,
            io = Dispatchers.IO,
            notifier = notifications,
            isAppVisible = visible::isVisible,
            canDownloadNow = { unmeteredOnly -> NetworkStatus.now(app).allows(unmeteredOnly) },
            log = ::log,
        )
        try {
            PinActionReceiver.register(app, controller)
        } catch (e: Exception) {
            log("no se pudo registrar el receptor de la notificación: ${e.message}")
        }
        visible.onVisible = { controller.onAppVisible() }
        pins = controller
        controller
    }

    fun transferQueue(context: Context, cache: CacheManager, settings: SettingsStore): WorkManagerTransferQueue =
        synchronized(this) {
            transfers ?: WorkManagerTransferQueue(context.applicationContext, cache, settings, scope).also { transfers = it }
        }

    /** Para el trabajo de fijados: la instancia de este proceso o, si la Application es el [AppGraph], la que cree. */
    fun pinsFor(context: Context): PinController? {
        pins?.let { return it }
        val graph = context.applicationContext as? AppGraph ?: return null
        return graph.pins as? PinController ?: pins
    }

    /** Para el worker del N+1: igual que [pinsFor]. */
    fun cacheFor(context: Context): CacheManager? {
        cache?.let { return it }
        val graph = context.applicationContext as? AppGraph ?: return null
        return try {
            graph.cache
        } catch (e: Throwable) {
            log("sin grafo para el worker: ${e.message}")
            null
        }
    }

    /**
     * Permiso de notificaciones (Android 13+, en tiempo de ejecución). Sin él los fijados se bajan igual, pero el
     * progreso solo se ve en el Administrador de tareas. La interfaz lo pide antes del primer «fijar».
     */
    const val NOTIFICATION_PERMISSION = Manifest.permission.POST_NOTIFICATIONS

    fun needsNotificationPermission(context: Context): Boolean =
        context.checkSelfPermission(NOTIFICATION_PERMISSION) != PackageManager.PERMISSION_GRANTED

    private fun visibility(app: Context): AppVisibility {
        visibility?.let { return it }
        val tracker = AppVisibility()
        (app as? Application)?.registerActivityLifecycleCallbacks(tracker)
        visibility = tracker
        return tracker
    }
}

/** StatFs sobre el volumen de la carpeta (o de su primer padre que exista). */
internal object AndroidStorageStats : StorageStats {
    override fun freeBytes(dir: File): Long {
        var existing: File? = dir.absoluteFile
        while (existing != null && !existing.exists()) existing = existing.parentFile
        val target = existing ?: return 0L
        return try {
            StatFs(target.path).availableBytes
        } catch (_: IllegalArgumentException) {
            target.usableSpace
        }
    }
}

/**
 * ¿Está la app a la vista? Cuenta actividades arrancadas (callbacks de la Application) y, por si el gestor se
 * creó con la actividad ya en marcha, mira también la importancia del proceso. Avisa al pasar a visible.
 */
internal class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0

    @Volatile
    var onVisible: (() -> Unit)? = null

    fun isVisible(): Boolean {
        if (started > 0) return true
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }

    override fun onActivityStarted(activity: Activity) {
        started++
        if (started == 1) onVisible?.invoke()
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}

/**
 * Extra del contrato (no cambia ninguna firma): la misma factoría con el [ProgressStore], para que el desalojo
 * sepa qué está terminado, sin empezar o leyéndose. Sin él, LRU puro. También vale llamar después a
 * `(graph.cache as DiskCacheManager).progress = graph.progress`.
 */
fun CacheFactories.cacheManager(
    context: Context,
    drive: DriveApi,
    settings: SettingsStore,
    library: LibraryRepository,
    progress: ProgressStore,
): CacheManager = CacheRuntime.cacheManager(context, drive, settings, library, progress)
