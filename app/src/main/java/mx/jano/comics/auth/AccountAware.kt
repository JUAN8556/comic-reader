package mx.jano.comics.auth

/**
 * Opcional para un [TokenProvider]: recuerda la cuenta de Google elegida para que las siguientes
 * autorizaciones sean silenciosas (sin selector de cuenta).
 *
 * Existe porque `AuthorizationResult.toGoogleSignInAccount()` (la única vía de 22.0.0 para saber qué cuenta
 * eligió el usuario) está deprecada y es anulable: si un día no trae la cuenta, el cliente de Drive la
 * rellena con el `user.emailAddress` de `about` (en Android, el nombre de una cuenta Google es su correo).
 */
interface AccountAware {
    /** Nombre de la cuenta fijada (el correo), o null si aún no se sabe. */
    val accountName: String?

    /** Fija la cuenta para las siguientes llamadas. Ignora nombres vacíos. */
    fun rememberAccount(name: String)
}
