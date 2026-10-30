package mx.jano.comics.archive

import java.io.File
import java.io.IOException

/**
 * Error al abrir o leer un cómic. Hereda de [IOException] para que quien ya captura E/S lo vea;
 * el mensaje está en español y se puede enseñar tal cual (la i18n llega después).
 */
open class ComicArchiveException(
    val kind: ArchiveKind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Formato que la app todavía no lee (7z, desconocido) o variante que no soporta (método ZIP raro, multivolumen, diccionario enorme). */
class UnsupportedArchiveException(kind: ArchiveKind, message: String, cause: Throwable? = null) :
    ComicArchiveException(kind, message, cause)

/** Archivo protegido con contraseña: la app no pide contraseñas. */
class EncryptedArchiveException(kind: ArchiveKind, message: String, cause: Throwable? = null) :
    ComicArchiveException(kind, message, cause)

/** Archivo dañado, truncado o que el motor no consigue leer. */
class CorruptArchiveException(kind: ArchiveKind, message: String, cause: Throwable? = null) :
    ComicArchiveException(kind, message, cause)

/** Textos de la sonda (español). Cuando llegue la i18n pasan a strings.xml. */
internal object ArchiveTexts {
    fun sevenZip(file: File) =
        "«${file.name}» es un 7z (aunque se llame .cbr o .cb7). Esta versión todavía no lee 7z: llegará con libarchive."
    fun unknown(file: File) = when (file.extension.lowercase()) {
        "cbt", "tar" -> "«${file.name}» es un TAR (.cbt). Esta versión todavía no lee TAR: llegará con libarchive."
        else -> "«${file.name}» no es un cómic que la app sepa leer: no empieza como ZIP, RAR, 7z ni PDF."
    }
    fun encrypted(file: File) =
        "«${file.name}» está protegido con contraseña y la app no pide contraseñas."
    fun encryptedPdf(file: File) =
        "«${file.name}» es un PDF protegido con contraseña y la app no pide contraseñas."
    fun corrupt(file: File, detail: String?) =
        "«${file.name}» está dañado o incompleto" + (detail?.let { ": $it" } ?: ".")
    fun multiVolume(file: File) =
        "«${file.name}» es una parte de un RAR multivolumen y faltan las otras partes."
    fun dictionaryTooBig(file: File, limitMb: Long) =
        "«${file.name}» usa un diccionario RAR mayor de $limitMb MB: no cabe en la memoria de la app."
    fun unsupportedRar(file: File, detail: String?) =
        "«${file.name}» usa una variante de RAR que el motor no soporta" + (detail?.let { ": $it" } ?: ".")
    fun unsupportedZipMethod(file: File, entry: String, method: Int) =
        "La página «$entry» de «${file.name}» usa un método de compresión ZIP que Android no trae ($method)."
    fun closed(file: File) = "«${file.name}» ya está cerrado."
    fun writeFailed(file: File, page: Int, detail: String?) =
        "No se pudo guardar la página $page de «${file.name}» en la tablet (¿almacenamiento lleno?)" +
            (detail?.let { ": $it" } ?: ".") + " El archivo no está dañado."
}
