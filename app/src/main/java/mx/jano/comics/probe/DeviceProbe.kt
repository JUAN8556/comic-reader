package mx.jano.comics.probe

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.Display
import android.webkit.WebView
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Sección 1 de la sonda: qué tablet es y si una app normal puede leer el modo de papel de TCL.
 * Cada lectura va en su propio try/catch: que falle una no tapa las demás.
 */
internal object DeviceProbe {
    val TABLES = listOf("system", "secure", "global")

    /**
     * Claves del modo NXTPAPER que adb SÍ vio en la tablet (docs/DISPOSITIVO-2026-10-29.md), cada una en su tabla.
     * La pregunta de la sonda es si una app normal las lee (sobre todo Settings.Secure) y qué valor toma
     * `nxtpaper_ink_style_state` en Normal / Color Paper / Ink Paper.
     */
    val KNOWN_KEYS = listOf(
        "secure" to "nxtpaper_ink_style_state",
        "secure" to "nxtpaper_style_before_apply_theme",
        "secure" to "nxtpaper_style_switch_count",
        "secure" to "installed_theme",
        "secure" to "reading_mode_be_on",
        "secure" to "reading_mode_display_screen",
        "secure" to "tct_color_temperature_activated",
        "system" to "color_mode_value",
        "system" to "adv_color_mode_value",
        "system" to "eyeprotect_kind",
        "system" to "eyeprotect_status",
        "system" to "nxtpaper_key_light_switches",
        "system" to "aikey_settings_single_press",
    )

    /** Candidatas que suponía la investigación (adb dice que no existen); se leen para confirmarlo desde la app. */
    val INK_KEYS = listOf(
        "tct_ink_mode_enable", "tct_ink_mode_type", "tct_ink_mode",
        "nxtpaper_mode", "ink_paper_mode", "color_paper_mode", "paper_mode", "eink_mode",
    )

    fun run(ctx: Context, log: SondaLog) {
        val cr = ctx.contentResolver
        step(log, "modelo") {
            "modelo: ${Build.MANUFACTURER} ${Build.MODEL} (marca ${Build.BRAND}, device ${Build.DEVICE}, product ${Build.PRODUCT})"
        }
        step(log, "android") {
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), parche ${Build.VERSION.SECURITY_PATCH}, build ${Build.DISPLAY}"
        }
        step(log, "play services") {
            val gaa = GoogleApiAvailability.getInstance()
            val status = gaa.isGooglePlayServicesAvailable(ctx)
            val versionName = runCatching {
                ctx.packageManager.getPackageInfo("com.google.android.gms", PackageManager.PackageInfoFlags.of(0)).versionName
            }.getOrElse { "no visible (${it::class.java.simpleName})" }
            "Play services: versionCode ${gaa.getApkVersion(ctx)}, versión $versionName, " +
                "estado ${if (status == 0) "0 (SUCCESS)" else "$status (no disponible)"}"
        }
        step(log, "webview") {
            val pkg = WebView.getCurrentWebViewPackage()
            "WebView: ${pkg?.packageName ?: "ninguno"} ${pkg?.versionName ?: ""}".trim()
        }
        step(log, "page size") { "tamaño de página de memoria: ${Os.sysconf(OsConstants._SC_PAGESIZE)} bytes" }
        step(log, "memoria") {
            val am = ctx.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            "memoria: RAM ${ProbeFormat.gb(info.totalMem)}, memoryClass ${am.memoryClass} MB, " +
                "largeMemoryClass ${am.largeMemoryClass} MB, maxMemory ${ProbeFormat.mb(Runtime.getRuntime().maxMemory())}"
        }
        step(log, "almacenamiento") {
            "espacio libre en la caché de la app: ${ProbeFormat.gb(ctx.cacheDir.usableSpace)}"
        }
        step(log, "pantalla") {
            val dm = ctx.getSystemService(DisplayManager::class.java)
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display.mode
            val modes = display.supportedModes.joinToString(", ") {
                "${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate.toInt()}"
            }
            val res = ctx.resources
            "pantalla: ${mode.physicalWidth}x${mode.physicalHeight} px físicos a ${mode.refreshRate} Hz (modos: $modes), " +
                "densidad ${res.displayMetrics.densityDpi} dpi, sw ${res.configuration.smallestScreenWidthDp} dp, " +
                "fontScale ${res.configuration.fontScale}"
        }
        step(log, "navigation_mode") {
            "navigation_mode: ${ProbeFormat.navigationModeLabel(readSetting(cr, "secure", "navigation_mode"))}"
        }
        step(log, "animaciones") {
            "animator_duration_scale: ${readSettingDescribed(cr, "global", Settings.Global.ANIMATOR_DURATION_SCALE)}"
        }

        logKnownKeys(cr, log, "NXTPAPER: claves que adb vio el 29-oct, leídas desde la app")
        log.line("NXTPAPER: claves candidatas de la investigación (se esperan null) en System, Secure y Global")
        for (table in TABLES) {
            for (key in INK_KEYS) {
                log.line("  $table/$key = ${readSettingDescribed(cr, table, key)}")
            }
        }
        for (table in TABLES) dumpTable(cr, table, log)
    }

    /**
     * Las 13 claves que adb vio, con su valor de ahora. «Leer modo ahora» lo repite en cada modo (Normal, Color Paper,
     * Ink Paper): así el valor por modo queda en el registro aunque el cambio no dispare ningún aviso de ajustes.
     */
    fun logKnownKeys(cr: ContentResolver, log: SondaLog, title: String) {
        log.line(title)
        for ((table, key) in KNOWN_KEYS) {
            log.line("  $table/$key = ${readSettingDescribed(cr, table, key)}")
        }
    }

    /** Lee un ajuste; lanza SecurityException si Android no deja (claves @hide sin @Readable desde Android 12). */
    fun readSetting(cr: ContentResolver, table: String, key: String): String? = when (table) {
        "system" -> Settings.System.getString(cr, key)
        "secure" -> Settings.Secure.getString(cr, key)
        else -> Settings.Global.getString(cr, key)
    }

    /** Valor entre comillas, «null» o la excepción, listo para el registro. */
    fun readSettingDescribed(cr: ContentResolver, table: String, key: String): String = try {
        readSetting(cr, table, key)?.let { "\"$it\"" } ?: "null"
    } catch (e: SecurityException) {
        "SecurityException: ${e.message}"
    } catch (t: Throwable) {
        SondaLog.describe(t)
    }

    /** Recorre content://settings/<tabla> y registra las claves cuyo nombre huele a modo de papel ([ProbeFormat.looksLikeInkSetting]). */
    private fun dumpTable(cr: ContentResolver, table: String, log: SondaLog) {
        val uri = Uri.parse("content://settings/$table")
        try {
            val cursor = cr.query(uri, arrayOf("name", "value"), null, null, null)
            if (cursor == null) {
                log.line("query $uri: devolvió null")
                return
            }
            cursor.use { c ->
                val nameCol = c.getColumnIndex("name")
                val valueCol = c.getColumnIndex("value")
                var total = 0
                var matches = 0
                while (c.moveToNext()) {
                    total++
                    val name = if (nameCol >= 0) c.getString(nameCol) else null
                    if (name != null && ProbeFormat.looksLikeInkSetting(name)) {
                        matches++
                        val value = if (valueCol >= 0) c.getString(valueCol) else null
                        log.line("  $table/$name = ${value?.let { "\"$it\"" } ?: "null"}")
                    }
                }
                log.line("query $uri: $total claves, $matches de modo de papel")
            }
        } catch (e: SecurityException) {
            log.line("query $uri: SecurityException: ${e.message}")
        } catch (t: Throwable) {
            log.error("query $uri", t)
        }
    }

    private inline fun step(log: SondaLog, what: String, block: () -> String) {
        try {
            log.line(block())
        } catch (t: Throwable) {
            log.error(what, t)
        }
    }
}

/**
 * Vigila las tres tablas de ajustes y avisa de cada clave que cambie: si al pulsar el botón NXTPAPER cambia
 * alguna, la app podrá detectar el modo sola (decisión 12). El aviso llega en el hilo principal.
 */
internal class SettingsWatcher(
    private val cr: ContentResolver,
    private val onChange: (table: String, key: String?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<ContentObserver>()

    val isActive: Boolean get() = observers.isNotEmpty()

    /** Devuelve una línea por tabla con «ok» o la excepción al registrar el observador. */
    fun start(): List<String> {
        if (isActive) return listOf("ya estaba activa")
        return DeviceProbe.TABLES.map { table ->
            val root = Uri.parse("content://settings/$table")
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val key = uri?.lastPathSegment?.takeIf { it != table }
                    onChange(table, key)
                }
            }
            try {
                cr.registerContentObserver(root, true, observer)
                observers += observer
                "$table: vigilando"
            } catch (t: Throwable) {
                "$table: ${SondaLog.describe(t)}"
            }
        }
    }

    fun stop() {
        observers.forEach { runCatching { cr.unregisterContentObserver(it) } }
        observers.clear()
    }
}
