package mx.jano.comics.auth

/** Play Services rechazó la autorización sin ofrecer pantalla de consentimiento (p. ej. DEVELOPER_ERROR). */
class AuthFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** El Intent devuelto por la pantalla de consentimiento no trae una autorización válida (cancelada o fallida). */
class ConsentFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)
