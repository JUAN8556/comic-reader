package mx.jano.comics.cache

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.library.Book
import mx.jano.comics.settings.SettingsStore
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Estado de la red por defecto: con internet de verdad (validada) y si no se paga por datos. */
data class NetworkStatus(val online: Boolean, val unmetered: Boolean) {
    /** ¿Se puede bajar ya con esta red? */
    fun allows(unmeteredOnly: Boolean): Boolean = online && (unmetered || !unmeteredOnly)

    companion object {
        val OFFLINE = NetworkStatus(online = false, unmetered = false)

        fun of(internet: Boolean, validated: Boolean, notMetered: Boolean, temporarilyNotMetered: Boolean) =
            NetworkStatus(online = internet && validated, unmetered = internet && (notMetered || temporarilyNotMetered))

        fun of(caps: NetworkCapabilities?): NetworkStatus = if (caps == null) OFFLINE else of(
            internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            temporarilyNotMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED),
        )

        /** Foto de ahora mismo (sin callback). */
        fun now(context: Context): NetworkStatus {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return OFFLINE
            return try {
                of(cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
            } catch (_: SecurityException) {
                OFFLINE
            }
        }
    }
}

/** La red por defecto en vivo (registerDefaultNetworkCallback → callbackFlow → StateFlow). Requiere ACCESS_NETWORK_STATE (lo trae WorkManager). */
internal fun networkStatusFlow(context: Context, scope: CoroutineScope): StateFlow<NetworkStatus> {
    val app = context.applicationContext
    val cm = app.getSystemService(ConnectivityManager::class.java)
    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                trySend(NetworkStatus.of(networkCapabilities))
            }

            override fun onLost(network: Network) {
                trySend(NetworkStatus.OFFLINE)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }
        .catch { emit(NetworkStatus.now(app)) } // sin permiso o sin servicio: al menos la foto
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, NetworkStatus.now(app))
}

/**
 * Lista de transferencias para la interfaz: lo que se está bajando (con su motivo) y, detrás, los N+1 en cola de
 * WorkManager que aún no empezaron. Pura, para probarla en la JVM.
 */
internal fun transferItems(
    downloads: Map<String, DownloadProgress>,
    queuedPrefetch: List<String>,
    purposeOf: (String) -> DownloadPurpose?,
    sizeOf: (String) -> Long?,
): List<TransferItem> {
    val prefetchSet = queuedPrefetch.toSet()
    fun reasonOf(id: String): String = when (purposeOf(id)) {
        DownloadPurpose.PIN -> TransferReasons.PIN
        DownloadPurpose.PREFETCH -> TransferReasons.PREFETCH
        DownloadPurpose.READ -> TransferReasons.READ
        null -> if (id in prefetchSet) TransferReasons.PREFETCH else TransferReasons.READ
    }
    val order = listOf(TransferReasons.READ, TransferReasons.PIN, TransferReasons.PREFETCH)
    val running = downloads.values
        .map { TransferItem(it.bookId, reasonOf(it.bookId), it.bytes, it.total ?: sizeOf(it.bookId)) }
        .sortedWith(compareBy({ order.indexOf(it.reason) }, { it.bookId }))
    val waiting = queuedPrefetch.distinct()
        .filter { it !in downloads }
        .map { TransferItem(it, TransferReasons.PREFETCH, 0, sizeOf(it)) }
    return running + waiting
}

/**
 * [TransferQueue] con WorkManager: el prefetch del N+1 es un OneTimeWorkRequest único por libro (KEEP), con
 * red sin medir si así está en Ajustes y almacenamiento no bajo; no es «expedited» (prioridad baja). El trabajo
 * llama a [DiskCacheManager.ensureLocalFor] con [DownloadPurpose.PREFETCH] (respeta tope y margen).
 */
class WorkManagerTransferQueue(
    context: Context,
    private val cache: CacheManager,
    private val settings: SettingsStore,
    scope: CoroutineScope,
) : TransferQueue {
    private val app = context.applicationContext
    private val workManager = WorkManager.getInstance(app)
    private val sizes = ConcurrentHashMap<String, Long>()

    private val network = networkStatusFlow(app, scope)
    override val isOnline: StateFlow<Boolean> =
        network.map { it.online }.stateIn(scope, SharingStarted.Eagerly, network.value.online)
    override val isUnmetered: StateFlow<Boolean> =
        network.map { it.unmetered }.stateIn(scope, SharingStarted.Eagerly, network.value.unmetered)

    private val queuedPrefetch = workManager.getWorkInfosByTagFlow(TAG)
        .map { infos -> infos.filter { !it.state.isFinished }.mapNotNull { bookIdOf(it.tags) } }
        .catch { emit(emptyList()) }
        .onStart { emit(emptyList()) }

    override val active: StateFlow<List<TransferItem>> =
        combine(cache.downloads, queuedPrefetch) { downloads, queued ->
            transferItems(
                downloads = downloads,
                queuedPrefetch = queued,
                purposeOf = { (cache as? DiskCacheManager)?.downloadPurpose(it) },
                sizeOf = { sizes[it] },
            )
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun prefetch(book: Book) {
        val prefs = settings.app.value
        if (!prefs.prefetchNext) return
        if (cache.availability(book) != Availability.CLOUD) return
        sizes[book.id] = book.size
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (prefs.wifiOnlyPrefetch) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        val request = OneTimeWorkRequest.Builder(PrefetchWorker::class.java)
            .setConstraints(constraints)
            .setInputData(Data.Builder().putString(PrefetchWorker.KEY_BOOK, json.encodeToString(Book.serializer(), book)).build())
            .addTag(TAG)
            .addTag(BOOK_TAG_PREFIX + book.id)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(uniqueName(book.id), ExistingWorkPolicy.KEEP, request)
    }

    /** Cancela la transferencia de ese libro, sea cual sea su motivo (prefetch en cola o descarga en curso). */
    override fun cancel(bookId: String) {
        workManager.cancelUniqueWork(uniqueName(bookId))
        sizes.remove(bookId)
        cache.cancelDownload(bookId)
    }

    companion object {
        const val TAG = "comics-prefetch"
        private const val BOOK_TAG_PREFIX = "comics-book:"
        private const val BACKOFF_SECONDS = 30L
        internal val json = Json { ignoreUnknownKeys = true }

        fun uniqueName(bookId: String) = "prefetch:$bookId"

        internal fun bookIdOf(tags: Set<String>): String? =
            tags.firstOrNull { it.startsWith(BOOK_TAG_PREFIX) }?.removePrefix(BOOK_TAG_PREFIX)
    }
}

/**
 * Baja el N+1. Si el proceso arrancó solo para esto y aún no hay grafo, lo intenta construir (si la Application
 * implementa [mx.jano.comics.AppGraph]) y, si no, reintenta más tarde. Si WorkManager lo para (se fue el Wi-Fi), la descarga se
 * cancela dejando el .part para reanudar.
 */
class PrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val book = inputData.getString(KEY_BOOK)?.let {
            try {
                WorkManagerTransferQueue.json.decodeFromString(Book.serializer(), it)
            } catch (_: Exception) {
                null
            }
        } ?: return Result.failure()
        val cache = CacheRuntime.cacheFor(applicationContext) ?: return retryOrFail()
        val disk = cache as? DiskCacheManager
        if (disk != null && !disk.settings.app.value.prefetchNext) return Result.success()
        return try {
            if (disk != null) disk.ensureLocalFor(book, DownloadPurpose.PREFETCH) else cache.ensureLocal(book)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: InsufficientSpaceException) {
            Result.failure() // no cabe sin tocar el margen: se queda en la nube
        } catch (_: CorruptDownloadException) {
            Result.failure()
        } catch (_: DownloadCancelledException) {
            Result.failure()
        } catch (e: DriveHttpException) {
            if (e.code == 429 || e.code >= 500) retryOrFail() else Result.failure()
        } catch (_: IOException) {
            retryOrFail()
        } catch (_: Exception) {
            Result.failure() // sesión, consentimiento…: el N+1 no merece insistir
        }
    }

    private fun retryOrFail(): Result = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

    companion object {
        const val KEY_BOOK = "book"
        private const val MAX_ATTEMPTS = 4
    }
}
