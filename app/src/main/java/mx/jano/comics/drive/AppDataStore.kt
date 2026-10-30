package mx.jano.comics.drive

import mx.jano.comics.auth.TokenProvider

/** CONTRATO COMÚN del MVP. Carpeta oculta de la app en Drive (scope drive.appdata): copia del progreso. */
interface AppDataStore {
    suspend fun list(): List<DriveFile>
    /** Crea o sustituye (por nombre) un fichero en appDataFolder. */
    suspend fun put(name: String, bytes: ByteArray, mimeType: String = "application/json"): DriveFile
    suspend fun get(name: String): ByteArray?
}

/** Lo rellena el frente «data» (REST: files?spaces=appDataFolder, files.create/update uploadType=media). */
object AppDataStores {
    fun rest(tokens: TokenProvider): AppDataStore = TODO("mvp/data")
}
