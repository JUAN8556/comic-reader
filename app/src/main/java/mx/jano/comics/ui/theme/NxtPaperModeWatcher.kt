package mx.jano.comics.ui.theme

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Fuente de los valores actuales de las claves NXTPAPER (id «tabla/clave» → valor). */
interface InkModeSource {
    val values: StateFlow<Map<String, String?>>
    fun refresh()
}

/**
 * Vigila con ContentObserver las claves de [NxtPaperKeys.WATCHED] (sin permisos: la sonda del 30-oct comprobó que se
 * leen sin SecurityException). Vive lo que el proceso; [refresh] relee por si un cambio no avisó.
 */
class NxtPaperModeWatcher(context: Context) : InkModeSource {
    private val cr: ContentResolver = context.applicationContext.contentResolver
    private val _values = MutableStateFlow(readAll())
    override val values: StateFlow<Map<String, String?>> = _values.asStateFlow()

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) = refresh()
    }

    init {
        for ((table, key) in NxtPaperKeys.WATCHED) {
            val uri = when (table) {
                "secure" -> Settings.Secure.getUriFor(key)
                "system" -> Settings.System.getUriFor(key)
                else -> Settings.Global.getUriFor(key)
            }
            try {
                cr.registerContentObserver(uri, false, observer)
            } catch (t: Throwable) {
                Log.w(TAG, "no se pudo vigilar $table/$key: ${t.javaClass.simpleName}")
            }
        }
    }

    override fun refresh() {
        val now = readAll()
        if (now != _values.value) _values.value = now
    }

    private fun readAll(): Map<String, String?> = NxtPaperKeys.WATCHED.associate { (table, key) ->
        NxtPaperKeys.id(table, key) to read(table, key)
    }

    private fun read(table: String, key: String): String? = try {
        when (table) {
            "secure" -> Settings.Secure.getString(cr, key)
            "system" -> Settings.System.getString(cr, key)
            else -> Settings.Global.getString(cr, key)
        }
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val TAG = "Comics"
    }
}
