package mx.jano.comics.drive

import mx.jano.comics.auth.TokenProvider

/**
 * Igual que [DriveApis.rest] pero las trazas (códigos HTTP, 206/200/416, 401, esperas por cuota, KB/s, versión del
 * parcial) van también a [log], además de a logcat. La sonda lo usa para que queden en `sonda.log`.
 * Nunca recibe el token ni la cabecera Authorization.
 */
fun DriveApis.rest(tokens: TokenProvider, log: (String) -> Unit): DriveRestApi =
    DriveRestApi(tokens, log = { message ->
        SondaLog.i(message)
        log(message)
    })
