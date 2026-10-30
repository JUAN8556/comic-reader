package mx.jano.comics.settings

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** CONTRATO COMÚN del MVP. Ajustes en DataStore o JSON atómico (sin Room). */
enum class FitMode { WIDTH, HEIGHT, BOTH }
enum class PageLayout { SINGLE, DOUBLE_AUTO, VERTICAL }
enum class ThemeMode { COVER_DARK, HIGH_CONTRAST, AUTO_NXTPAPER }

@Serializable
data class ReaderSettings(
    val fit: FitMode = FitMode.BOTH,
    val layout: PageLayout = PageLayout.DOUBLE_AUTO,   // en horizontal; en vertical siempre una página salvo VERTICAL
    val rtlGlobal: Boolean = false,
    val volumeKeys: Boolean = true,
    val keepScreenOn: Boolean = true,
    val tapZones: Boolean = true,
)

@Serializable
data class AppSettings(
    val rootFolderId: String? = null,
    val rootFolderName: String? = null,
    val theme: ThemeMode = ThemeMode.AUTO_NXTPAPER,
    val cacheLimitGb: Int = 20,
    val minFreeGb: Int = 15,
    val wifiOnlyPrefetch: Boolean = true,
    val prefetchNext: Boolean = true,
)

interface SettingsStore {
    val app: StateFlow<AppSettings>
    val reader: StateFlow<ReaderSettings>
    suspend fun updateApp(transform: (AppSettings) -> AppSettings)
    suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings)
    /** RTL efectivo para un libro: libro > carpeta (la más cercana) > global. */
    fun rtlFor(bookPath: String): Boolean
    /** null = heredar. Se aplica a un libro (ruta completa) o a una carpeta (ruta de carpeta). */
    suspend fun setRtl(path: String, rtl: Boolean?)
}

/** Lo rellena el frente «data». */
object SettingsFactories {
    fun store(context: Context): SettingsStore = TODO("mvp/data")
}
