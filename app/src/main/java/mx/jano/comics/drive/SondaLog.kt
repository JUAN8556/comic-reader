package mx.jano.comics.drive

import android.util.Log

/** Trazas de la sonda en logcat (tag "Sonda"). Aparte para que las pruebas JVM no toquen android.util.Log. */
internal object SondaLog {
    fun i(message: String) {
        Log.i("Sonda", message)
    }
}
