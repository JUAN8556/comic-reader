package mx.jano.comics.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationClient
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/** Estado de la autorización para la pantalla de diagnóstico de la sonda (sin el token). */
data class AuthDiagnostics(
    val accountName: String?,
    val grantedScopes: Set<String>,
    /** Momento del último consentimiento en pantalla (epoch ms). La prueba de los 8 días cuenta desde aquí. */
    val consentAtMillis: Long?,
    /** Último token obtenido sin pantalla (epoch ms). */
    val lastSilentAtMillis: Long?,
)

/**
 * [TokenProvider] con el AuthorizationClient de Play Services (play-services-auth 22.0.0).
 *
 * - Funciona con el Context de la aplicación, sin Activity: los workers y jobs piden token igual que la UI.
 *   Si hace falta pantalla lanza [NeedsConsentException] y la UI dispara el PendingIntent.
 * - Pide drive.readonly y drive.appdata en la MISMA petición (un solo consentimiento).
 * - Sin requestOfflineAccess (no hay backend) y sin client ID en el código: el cliente Android de OAuth se
 *   reconoce por applicationId + SHA-1 de la firma, registrados en la consola.
 * - Recuerda la cuenta elegida en SharedPreferences privadas y la fija con setAccount() para que authorize()
 *   sea silencioso. No pide GET_ACCOUNTS.
 * - Sin caché propia de tokens: Play Services ya cachea el token de ~1 h y lo renueva solo.
 */
class PlayServicesTokenProvider private constructor(context: Context) : TokenProvider, AccountAware {

    private val appContext: Context = context.applicationContext ?: context
    private val client: AuthorizationClient = Identity.getAuthorizationClient(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Último token entregado; solo para saber cuál limpiar con forceRefresh. Nunca se registra en el log. */
    @Volatile
    private var lastToken: String? = null

    override val scopes: List<String> = listOf(Scopes.DRIVE_READONLY, Scopes.DRIVE_APPDATA)

    override val accountName: String?
        get() = prefs.getString(KEY_ACCOUNT, null)

    override fun rememberAccount(name: String) {
        if (name.isBlank() || name == accountName) return
        prefs.edit().putString(KEY_ACCOUNT, name).apply()
        Log.i(TAG, "cuenta fijada para las próximas autorizaciones")
    }

    /** Olvida la cuenta fijada: la próxima autorización vuelve a mostrar el selector de Play Services. */
    fun forgetAccount() {
        prefs.edit().remove(KEY_ACCOUNT).apply()
    }

    /**
     * Si el usuario desmarcó drive.appdata en el consentimiento, la app sigue pidiendo solo drive.readonly
     * (en silencio). Esto vuelve a pedir los dos en la próxima autorización (habrá pantalla).
     */
    fun forgetDeclinedScopes() {
        prefs.edit().remove(KEY_GRANTED).apply()
    }

    fun diagnostics(): AuthDiagnostics = AuthDiagnostics(
        accountName = accountName,
        grantedScopes = prefs.getStringSet(KEY_GRANTED, null).orEmpty().toSet(),
        consentAtMillis = prefs.getLong(KEY_CONSENT_AT, 0L).takeIf { it > 0 },
        lastSilentAtMillis = prefs.getLong(KEY_LAST_SILENT_AT, 0L).takeIf { it > 0 },
    )

    override suspend fun accessToken(forceRefresh: Boolean): String {
        if (forceRefresh) {
            // Sin el token anterior en memoria (proceso nuevo), se pide el que tenga Play Services en caché
            // para poder limpiarlo; después se pide otro.
            val stale = lastToken ?: authorize()
            invalidate(stale)
        }
        return authorize()
    }

    override suspend fun invalidate(token: String) {
        if (token == lastToken) lastToken = null
        val t0 = System.nanoTime()
        try {
            client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await()
            Log.i(TAG, "clearToken (AuthorizationClient) en ${elapsedMs(t0)} ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "clearToken de AuthorizationClient falló (${describe(e)}); pruebo GoogleAuthUtil.clearToken")
            try {
                withContext(Dispatchers.IO) { GoogleAuthUtil.clearToken(appContext, token) }
                Log.i(TAG, "clearToken (GoogleAuthUtil) en ${elapsedMs(t0)} ms")
            } catch (e2: CancellationException) {
                throw e2
            } catch (e2: Exception) {
                // Sin limpiar, el siguiente authorize() puede devolver el mismo token y Drive volverá a dar 401.
                Log.w(TAG, "GoogleAuthUtil.clearToken también falló (${describe(e2)})")
            }
        }
    }

    override fun completeConsent(data: Intent?): List<String> {
        if (data == null) {
            throw ConsentFailedException(TEXT_CONSENT_NO_DATA)
        }
        val result = try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            Log.w(TAG, "consentimiento fallido: ${describe(e)}")
            throw ConsentFailedException("$TEXT_CONSENT_FAILED (${describe(e)})", e)
        }
        val now = System.currentTimeMillis()
        val granted = remember(result)
        prefs.edit().putLong(KEY_CONSENT_AT, now).apply()
        result.accessToken?.let { lastToken = it }
        Log.i(
            TAG,
            "consentimiento completado: scopes concedidos=$granted, cuenta conocida=${accountName != null}, " +
                "token=${result.accessToken?.length ?: 0} caracteres",
        )
        return granted
    }

    /** Una llamada a authorize(): silenciosa si ya hay consentimiento; si no, [NeedsConsentException]. */
    private suspend fun authorize(): String {
        val account = accountName
        val requested = scopesToRequest()
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(requested.map { Scope(it) })
            .apply { if (account != null) setAccount(Account(account, GOOGLE_ACCOUNT_TYPE)) }
            .build()

        val t0 = System.nanoTime()
        val result = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            Log.w(TAG, "authorize falló en ${elapsedMs(t0)} ms: ${describe(e)}")
            val hint = if (e.statusCode == CommonStatusCodes.DEVELOPER_ERROR) TEXT_DEVELOPER_ERROR_HINT else ""
            throw AuthFailedException("$TEXT_AUTHORIZE_FAILED (${describe(e)})$hint", e)
        }
        val ms = elapsedMs(t0)

        if (result.hasResolution()) {
            Log.i(
                TAG,
                "authorize pide pantalla en $ms ms (cuenta fijada=${account != null}, scopes pedidos=${requested.size})",
            )
            throw NeedsConsentException(result.pendingIntent)
        }

        val token = result.accessToken
            ?: throw AuthFailedException(TEXT_NO_TOKEN)
        remember(result)
        lastToken = token
        val now = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_SILENT_AT, now).apply()
        val consentAt = prefs.getLong(KEY_CONSENT_AT, 0L)
        val sinceConsent = if (consentAt > 0) "%.2f días desde el consentimiento".format((now - consentAt) / 86_400_000.0) else "consentimiento sin fecha"
        Log.i(
            TAG,
            "authorize silencioso en $ms ms: token de ${token.length} caracteres, cuenta fijada=${account != null}, " +
                "scopes=${requested.size}, $sinceConsent",
        )
        return token
    }

    /**
     * Pide los dos scopes salvo que el usuario haya concedido drive.readonly y desmarcado drive.appdata:
     * entonces solo drive.readonly, para no volver a sacar la pantalla en cada arranque.
     */
    private fun scopesToRequest(): List<String> {
        val granted = prefs.getStringSet(KEY_GRANTED, null) ?: return scopes
        return if (Scopes.DRIVE_READONLY in granted && Scopes.DRIVE_APPDATA !in granted) {
            listOf(Scopes.DRIVE_READONLY)
        } else {
            scopes
        }
    }

    /** Guarda la cuenta (si el resultado la trae) y los scopes concedidos. Devuelve los scopes. */
    private fun remember(result: AuthorizationResult): List<String> {
        accountFrom(result)?.let { rememberAccount(it) }
        val granted: List<String> = result.grantedScopes // @NonNull en 22.0.0
        if (granted.isNotEmpty()) {
            prefs.edit().putStringSet(KEY_GRANTED, granted.toSet()).apply()
        }
        return granted
    }

    /**
     * En 22.0.0 la cuenta solo sale por toGoogleSignInAccount(), deprecado y anulable (comprobado con javap:
     * AuthorizationResult no tiene getAccount ni getEmail). Si viene null, [AccountAware] permite rellenarla
     * desde Drive (about.user.emailAddress).
     */
    @Suppress("DEPRECATION")
    private fun accountFrom(result: AuthorizationResult): String? {
        val signIn = result.toGoogleSignInAccount() ?: return null
        return signIn.account?.name ?: signIn.email
    }

    private fun describe(e: Exception): String = when (e) {
        is ApiException -> "código ${e.statusCode} ${CommonStatusCodes.getStatusCodeString(e.statusCode)}"
        else -> e.javaClass.simpleName
    }

    private fun elapsedMs(t0: Long): Long = (System.nanoTime() - t0) / 1_000_000

    companion object {
        private const val TAG = "Sonda"
        private const val PREFS_NAME = "auth"
        private const val KEY_ACCOUNT = "account_name"
        private const val KEY_GRANTED = "granted_scopes"
        private const val KEY_CONSENT_AT = "consent_at_ms"
        private const val KEY_LAST_SILENT_AT = "last_silent_at_ms"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"

        // Textos de la sonda (la i18n llega después).
        private const val TEXT_CONSENT_NO_DATA = "La pantalla de permisos se cerró sin respuesta (¿cancelada?)"
        private const val TEXT_CONSENT_FAILED = "Google no completó el permiso"
        private const val TEXT_AUTHORIZE_FAILED = "Play Services rechazó la autorización"
        private const val TEXT_DEVELOPER_ERROR_HINT =
            ". Revisa en la consola que el cliente Android tenga este paquete y la SHA-1 de la firma"
        private const val TEXT_NO_TOKEN = "Play Services no devolvió token ni pantalla de permisos"

        @Volatile
        private var instance: PlayServicesTokenProvider? = null

        /** Una instancia por proceso (comparten SharedPreferences y el último token). */
        fun get(context: Context): PlayServicesTokenProvider =
            instance ?: synchronized(this) {
                instance ?: PlayServicesTokenProvider(context).also { instance = it }
            }
    }
}
