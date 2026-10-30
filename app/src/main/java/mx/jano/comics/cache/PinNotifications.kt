package mx.jano.comics.cache

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * La notificación de los libros fijados (canal propio, importancia baja: sin sonido). Mientras corre el trabajo
 * UIDT la ata [PinJobService] con setNotification; al terminar queda «despegada» (DETACH) con el estado final.
 * Las acciones Pausar / Reanudar / Cancelar van por broadcast explícito a [PinActionReceiver], declarado en el
 * manifiesto y no exportado: así responden aunque el sistema haya matado el proceso con la notificación a la vista
 * (con un receptor registrado en tiempo de ejecución, los botones quedaban muertos hasta abrir la app).
 */
internal class PinNotifications(private val context: Context) : PinNotifier {
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, CacheStrings.CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = CacheStrings.CHANNEL_DESCRIPTION
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun starting(): Notification = base()
        .setContentTitle(CacheStrings.PIN_STARTING)
        .setProgress(0, 0, true)
        .setOngoing(true)
        .build()

    fun running(progress: PinProgress?, bytesPerSecond: Long): Notification {
        val b = base().setOngoing(true)
        if (progress == null) return b.setContentTitle(CacheStrings.PIN_STARTING).setProgress(0, 0, true).build()
        b.setContentTitle(CacheStrings.pinTitle(progress.done, progress.total))
        progress.current?.let {
            b.setContentText(CacheStrings.pinCurrent(it, progress.bytesDone, progress.bytesTotal, bytesPerSecond))
        }
        b.setProgress(PROGRESS_MAX, permille(progress), progress.bytesTotal <= 0)
        b.addAction(action(android.R.drawable.ic_media_pause, CacheStrings.ACTION_PAUSE, ACTION_PAUSE))
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, CacheStrings.ACTION_CANCEL, ACTION_CANCEL))
        return b.build()
    }

    fun waiting(unmeteredOnly: Boolean, progress: PinProgress?): Notification {
        val b = base()
            .setContentTitle(if (unmeteredOnly) CacheStrings.PIN_WAITING_WIFI else CacheStrings.PIN_WAITING_NETWORK)
        progress?.let {
            b.setContentText(CacheStrings.pinTitle(it.done, it.total))
            b.setProgress(PROGRESS_MAX, permille(it), false)
        }
        b.addAction(action(android.R.drawable.ic_media_pause, CacheStrings.ACTION_PAUSE, ACTION_PAUSE))
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, CacheStrings.ACTION_CANCEL, ACTION_CANCEL))
        return b.build()
    }

    fun paused(progress: PinProgress?): Notification {
        val b = base().setContentTitle(CacheStrings.PIN_PAUSED)
        progress?.let {
            b.setContentText(CacheStrings.pinTitle(it.done, it.total))
            b.setProgress(PROGRESS_MAX, permille(it), false)
        }
        b.addAction(action(android.R.drawable.ic_media_play, CacheStrings.ACTION_RESUME, ACTION_RESUME))
        b.addAction(action(android.R.drawable.ic_menu_close_clear_cancel, CacheStrings.ACTION_CANCEL, ACTION_CANCEL))
        return b.build()
    }

    fun done(count: Int): Notification = base()
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle(CacheStrings.pinDone(count))
        .setAutoCancel(true)
        .build()

    fun noSpace(detail: String?): Notification = base()
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(CacheStrings.PIN_NO_SPACE)
        .apply { detail?.let { setContentText(it).setStyle(Notification.BigTextStyle().bigText(it)) } }
        .addAction(action(android.R.drawable.ic_media_play, CacheStrings.ACTION_RESUME, ACTION_RESUME))
        .setAutoCancel(true)
        .build()

    fun needsApp(): Notification = base()
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(CacheStrings.PIN_NEEDS_APP)
        .setAutoCancel(true)
        .build()

    fun failed(detail: String?): Notification = base()
        .setSmallIcon(android.R.drawable.stat_notify_error)
        .setContentTitle(CacheStrings.PIN_FAILED)
        .apply { detail?.let { setContentText(it) } }
        .addAction(action(android.R.drawable.ic_media_play, CacheStrings.ACTION_RESUME, ACTION_RESUME))
        .setAutoCancel(true)
        .build()

    /** Publica fuera del trabajo (sin permiso POST_NOTIFICATIONS, el sistema la descarta sin error). */
    fun post(notification: Notification) {
        try {
            ensureChannel()
            manager.notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // sin permiso de notificaciones: el estado sigue en la app
        }
    }

    override fun showPaused(progress: PinProgress?) = post(paused(progress))
    override fun showNeedsApp() = post(needsApp())
    override fun clear() = manager.cancel(NOTIFICATION_ID)

    private fun base(): Notification.Builder {
        ensureChannel()
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .apply { openApp()?.let { setContentIntent(it) } }
    }

    private fun openApp(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, REQUEST_OPEN, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun action(icon: Int, title: String, suffix: String): Notification.Action {
        val intent = Intent(actionName(context, suffix)).setClass(context, PinActionReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            requestCodeFor(suffix),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(Icon.createWithResource(context, icon), title, pending).build()
    }

    private fun permille(p: PinProgress): Int =
        if (p.bytesTotal <= 0) 0 else (p.bytesDone.coerceIn(0, p.bytesTotal) * PROGRESS_MAX / p.bytesTotal).toInt()

    companion object {
        const val CHANNEL_ID = "pins"
        const val NOTIFICATION_ID = 4201
        private const val PROGRESS_MAX = 1000
        private const val REQUEST_OPEN = 4210
        const val ACTION_PAUSE = "PIN_PAUSE"
        const val ACTION_RESUME = "PIN_RESUME"
        const val ACTION_CANCEL = "PIN_CANCEL"

        fun actionName(context: Context, suffix: String) = "${context.packageName}.cache.$suffix"

        private fun requestCodeFor(suffix: String) = when (suffix) {
            ACTION_PAUSE -> 4211
            ACTION_RESUME -> 4212
            else -> 4213
        }
    }
}

/**
 * Recibe las acciones de la notificación. Declarado en el manifiesto (no exportado: solo la propia app, por intent
 * explícito). Si el proceso estaba muerto, el sistema lo arranca y el gestor de fijados se crea aquí (pins.json
 * guarda la cola). Las acciones corren en segundo plano: se mantiene vivo el broadcast hasta que se guardan.
 */
class PinActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val controller = try {
            CacheRuntime.pinsFor(context)
        } catch (e: Exception) {
            CacheRuntime.log("notificación: no se pudo preparar el gestor de fijados: ${e.message}")
            null
        } ?: return
        val action = intent.action
        val pending = goAsync()
        CacheRuntime.scope.launch {
            try {
                controller.awaitReady()
                when (action) {
                    PinNotifications.actionName(context, PinNotifications.ACTION_PAUSE) -> controller.pause()
                    PinNotifications.actionName(context, PinNotifications.ACTION_RESUME) -> controller.resume()
                    PinNotifications.actionName(context, PinNotifications.ACTION_CANCEL) -> controller.cancel()
                }
                delay(SETTLE_MS) // pause/resume/cancel se lanzan en el ámbito del gestor: tiempo para guardar pins.json
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val SETTLE_MS = 2_000L
    }
}
