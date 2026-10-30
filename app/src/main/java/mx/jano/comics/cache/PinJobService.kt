package mx.jano.comics.cache

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Programa la bajada de los fijados como trabajo UIDT (user-initiated data transfer, API 34+): sin cuotas, con
 * notificación obligatoria y visible en el Administrador de tareas. Solo se puede programar con la app visible (o
 * justo tras tocar una acción de la notificación); si no, schedule() devuelve RESULT_FAILURE.
 */
internal class JobPinScheduler(private val context: Context) : PinScheduler {
    /** Espacio de nombres propio (API 34+): sus id no chocan con los de WorkManager ni con nadie. */
    private val jobs: JobScheduler
        get() = context.getSystemService(JobScheduler::class.java).forNamespace(NAMESPACE)

    override fun schedule(estimatedBytes: Long, unmeteredOnly: Boolean): Boolean {
        val estimate = if (estimatedBytes > 0) estimatedBytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong()
        val builder = JobInfo.Builder(JOB_ID, ComponentName(context, PinJobService::class.java))
            .setUserInitiated(true)
            .setRequiredNetworkType(if (unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY)
            .setEstimatedNetworkBytes(estimate, 0)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
        // Con trozo mínimo el sistema no trata la tanda como «todo o nada» (una serie entera puede ser de GB).
        if (estimatedBytes > 0) builder.setMinimumNetworkChunkBytes(minOf(MIN_CHUNK_BYTES, estimatedBytes))
        return jobs.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel() = jobs.cancel(JOB_ID)

    override fun isScheduled(): Boolean = jobs.getPendingJob(JOB_ID) != null

    companion object {
        const val NAMESPACE = "mx.jano.comics.pins"
        const val JOB_ID = 1
        private const val BACKOFF_MS = 30_000L
        private const val MIN_CHUNK_BYTES = 1_000_000L
    }
}

/**
 * El trabajo UIDT que baja la cola de fijados ([PinController.runQueue]). Notificación en menos de 10 s (si no, ANR):
 * se pone al entrar. onStopJob devuelve true para que el sistema lo reprograme salvo si lo paró la app (pausa,
 * cancelar) o el usuario desde el sistema (entonces queda en pausa; al volver a la app se reanuda a mano).
 * Si el proceso arrancó solo para este trabajo y no hay grafo, se avisa de abrir la app y se termina: la cola está
 * en pins.json y la app la concilia y reprograma al volver a primer plano.
 */
class PinJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var work: Job? = null

    @Volatile
    private var transferred = 0L

    private val notifications by lazy { PinNotifications(applicationContext) }

    override fun onStartJob(params: JobParameters): Boolean {
        setNotification(params, PinNotifications.NOTIFICATION_ID, notifications.starting(), JOB_END_NOTIFICATION_POLICY_DETACH)
        val controller = try {
            CacheRuntime.pinsFor(applicationContext)
        } catch (e: Throwable) {
            Log.w(TAG, "sin grafo para el trabajo de fijados: ${e.message}")
            null
        }
        if (controller == null) {
            notifications.post(notifications.needsApp())
            return false
        }
        transferred = 0
        work = scope.launch {
            var lastNotify = 0L
            var startBytes = -1L
            val listener = PinRunListener { progress, bytesPerSecond ->
                if (startBytes < 0) startBytes = progress.bytesDone
                transferred = (progress.bytesDone - startBytes).coerceAtLeast(0)
                val now = SystemClock.elapsedRealtime()
                if (now - lastNotify >= NOTIFY_INTERVAL_MS) {
                    lastNotify = now
                    try {
                        setNotification(params, PinNotifications.NOTIFICATION_ID, notifications.running(progress, bytesPerSecond), JOB_END_NOTIFICATION_POLICY_DETACH)
                        updateTransferredNetworkBytes(params, transferred, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "no se pudo actualizar la notificación: ${e.message}")
                    }
                }
            }
            val result = try {
                controller.runFromJob(listener)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PinRunResult.Failed(e.message ?: e::class.java.simpleName)
            }
            Log.i(TAG, "trabajo de fijados: $result")
            when (result) {
                is PinRunResult.Completed -> {
                    if (result.done > 0) notifications.post(notifications.done(result.done))
                    jobFinished(params, false)
                    if (result.done == 0) notifications.clear()
                    controller.onJobEnded()
                }
                PinRunResult.Paused -> {
                    notifications.post(notifications.paused(controller.progress.value))
                    jobFinished(params, false)
                }
                is PinRunResult.Retry -> {
                    notifications.post(notifications.waiting(controller.unmeteredOnly, controller.progress.value))
                    jobFinished(params, true)
                }
                is PinRunResult.NoSpace -> {
                    notifications.post(notifications.noSpace(result.message))
                    jobFinished(params, false)
                }
                is PinRunResult.NeedsApp -> {
                    notifications.post(notifications.needsApp())
                    jobFinished(params, false)
                }
                is PinRunResult.Failed -> {
                    notifications.post(notifications.failed(result.message))
                    jobFinished(params, false)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        val reason = params.stopReason
        val controller = CacheRuntime.pins
        if (reason == JobParameters.STOP_REASON_USER) controller?.markStoppedByUser()
        Log.i(TAG, "trabajo de fijados parado (motivo $reason)")
        when {
            controller == null -> Unit
            reason == JobParameters.STOP_REASON_USER || controller.isPausedNow ->
                notifications.post(notifications.paused(controller.progress.value))
            controller.queueSize == 0 -> notifications.clear()
            reason != JobParameters.STOP_REASON_CANCELLED_BY_APP ->
                notifications.post(notifications.waiting(controller.unmeteredOnly, controller.progress.value))
        }
        return reason != JobParameters.STOP_REASON_CANCELLED_BY_APP && reason != JobParameters.STOP_REASON_USER
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * JobService.getTransferredDownloadBytes(JobParameters) es @hide en el SDK; está pensada para que el sistema
     * pregunte el progreso de un trabajo de transferencia si no se le informa a menudo, y la base lanza
     * «Not implemented» con targetSdk > 33. En Android 15 el sistema aún no la llama (TODO 255371817 en AOSP) y
     * aquí se informa cada segundo con updateTransferredNetworkBytes; por si una versión futura la llama, la misma
     * firma JVM la sobrescribe. @Keep para que R8 no la quite ni la renombre (no ve el método del padre).
     */
    @Keep
    fun getTransferredDownloadBytes(params: JobParameters): Long = transferred

    @Keep
    fun getTransferredUploadBytes(params: JobParameters): Long = 0

    private companion object {
        const val TAG = "Cache"
        const val NOTIFY_INTERVAL_MS = 1_000L
    }
}
