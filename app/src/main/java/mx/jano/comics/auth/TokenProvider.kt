package mx.jano.comics.auth

import android.app.PendingIntent
import android.content.Context

/**
 * CONTRATO COMÚN (no cambiar la firma sin tocar a todos los que la usan).
 * Quien da tokens de acceso a Google Drive. La implementación principal usa el
 * AuthorizationClient de Play Services (play-services-auth 22); el plan B sería una cuenta de servicio.
 */
interface TokenProvider {
    /**
     * Devuelve un token de acceso vigente (~1 h). Si hace falta que el usuario consienta en pantalla,
     * lanza [NeedsConsentException] con el PendingIntent que hay que disparar con
     * ActivityResultContracts.StartIntentSenderForResult; tras el resultado, [completeConsent].
     */
    suspend fun accessToken(forceRefresh: Boolean = false): String

    /** Invalida un token que el servidor rechazó con 401 para que el siguiente [accessToken] traiga otro. */
    suspend fun invalidate(token: String)

    /** Procesa el Intent devuelto por la pantalla de consentimiento. Devuelve los scopes concedidos. */
    fun completeConsent(data: android.content.Intent?): List<String>

    /** Scopes que la app pide. */
    val scopes: List<String>
}

class NeedsConsentException(val pendingIntent: PendingIntent?) : Exception("Hace falta consentimiento del usuario")

object Scopes {
    const val DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    const val DRIVE_APPDATA = "https://www.googleapis.com/auth/drive.appdata"
}

object AuthProviders {
    /** Implementación con Play Services (AuthorizationClient). Vale con el Context de la aplicación. */
    fun playServices(context: Context): TokenProvider = PlayServicesTokenProvider.get(context)
}
