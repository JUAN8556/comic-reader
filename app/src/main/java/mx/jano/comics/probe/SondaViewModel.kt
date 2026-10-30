package mx.jano.comics.probe

import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mx.jano.comics.BuildConfig
import mx.jano.comics.archive.ArchiveSniffer
import mx.jano.comics.archive.ComicArchive
import mx.jano.comics.archive.ComicArchives
import mx.jano.comics.auth.AuthProviders
import mx.jano.comics.auth.NeedsConsentException
import mx.jano.comics.auth.TokenProvider
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveApis
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.drive.DriveHttpException
import mx.jano.comics.reader.PageFiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Progreso de la descarga para la pantalla: bytes en disco, total esperado y velocidad ya formateada. */
data class DownloadProgress(val bytes: Long, val total: Long?, val speed: String)

/** Archivo abierto con el contrato ComicArchive; lo usa «Leer». Se compara por identidad. */
class OpenedArchive(val fileId: String, val fileName: String, val archive: ComicArchive, val summary: String)

data class SondaUiState(
    val busy: String? = null,
    val watching: Boolean = false,
    val consent: PendingIntent? = null,
    val authStatus: String = "sin conectar",
    val grantedScopes: List<String> = emptyList(),
    val firstTokenInfo: String? = null,
    val sample: DriveFile? = null,
    val candidates: List<DriveFile> = emptyList(),
    val localBytes: Long = 0L,
    val downloading: Boolean = false,
    val progress: DownloadProgress? = null,
    val opened: OpenedArchive? = null,
    val rtl: Boolean = false,
    val reading: Boolean = false,
)

/**
 * Estado y trabajo de la sonda. Vive en un ViewModel para sobrevivir al giro; todo lo lento va a Dispatchers.IO
 * dentro de [runTask], que registra clase + mensaje de cualquier excepción (también los TODO del contrato).
 * Una tarea a la vez: los botones se apagan mientras hay una en marcha; «Cortar» cancela la descarga.
 */
class SondaViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val sondaDir: File? = app.getExternalFilesDir("sonda")
    val log = SondaLog(sondaDir?.let { File(it, "sonda.log") }, viewModelScope)

    /** Descargas, extracciones de prueba y páginas del lector: todo en la caché privada. */
    private val workDir = File(app.cacheDir, "sonda")

    private val _ui = MutableStateFlow(SondaUiState())
    val ui: StateFlow<SondaUiState> = _ui.asStateFlow()

    private var tokens: TokenProvider? = null
    private var drive: DriveApi? = null
    private var work: Job? = null
    private var consentShownAt = 0L
    private val watcher = SettingsWatcher(app.contentResolver) { table, key -> onSettingChanged(table, key) }

    init {
        log.header("=== sesión ${ZonedDateTime.now()} · ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} ===")
        probeDevice()
    }

    // ---------------------------------------------------------------- 1. Dispositivo

    fun probeDevice() = runTask(TASK_DEVICE) {
        DeviceProbe.run(ctx, log)
        if (!watcher.isActive) withContext(Dispatchers.Main) { startWatching() }
    }

    fun toggleWatch() {
        if (watcher.isActive) {
            watcher.stop()
            log.line("vigilancia de ajustes apagada")
        } else {
            startWatching()
        }
        _ui.update { it.copy(watching = watcher.isActive) }
    }

    private fun startWatching() {
        watcher.start().forEach { log.line("vigilancia de ajustes · $it") }
        _ui.update { it.copy(watching = watcher.isActive) }
    }

    private fun onSettingChanged(table: String, key: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            if (key == null) {
                log.line("ajuste cambiado: $table (sin nombre de clave)")
                return@launch
            }
            val value = DeviceProbe.readSettingDescribed(ctx.contentResolver, table, key)
            val mark = if (ProbeFormat.looksLikeInkSetting(key)) "  [modo de papel]" else ""
            log.line("ajuste cambiado: $table/$key = $value$mark")
        }
    }

    // ---------------------------------------------------------------- 2. Conectar Drive

    private fun tokenProvider(): TokenProvider = tokens ?: AuthProviders.playServices(ctx).also {
        tokens = it
        log.line("scopes que se piden: ${it.scopes.joinToString(", ")}")
    }

    private fun driveApi(): DriveApi = drive ?: DriveApis.rest(tokenProvider()).also { drive = it }

    fun connect() = runTask(TASK_CONNECT) { requestToken(forceRefresh = false, label = TASK_CONNECT) }

    /** La prueba de los 8 días: debe dar token sin pantalla. */
    fun silentToken() = runTask(TASK_SILENT) { requestToken(forceRefresh = false, label = TASK_SILENT) }

    fun forceToken() = runTask(TASK_FORCE) { requestToken(forceRefresh = true, label = TASK_FORCE) }

    private suspend fun requestToken(forceRefresh: Boolean, label: String) {
        val provider = tokenProvider()
        val t0 = elapsed()
        try {
            val token = provider.accessToken(forceRefresh)
            val ms = elapsed() - t0
            log.line("$label: token obtenido (${token.length} caracteres) en $ms ms, SIN pantalla (forceRefresh=$forceRefresh)")
            onTokenOk("token sin pantalla en $ms ms")
        } catch (e: NeedsConsentException) {
            log.line("$label: HIZO FALTA PANTALLA de consentimiento (lo supo en ${elapsed() - t0} ms, forceRefresh=$forceRefresh)")
            askConsent(e)
        }
    }

    private fun askConsent(e: NeedsConsentException) {
        val pi = e.pendingIntent
        if (pi == null) {
            log.line("NeedsConsentException sin PendingIntent: no hay hoja que mostrar")
            return
        }
        _ui.update { it.copy(consent = pi, authStatus = "esperando la hoja de consentimiento") }
    }

    /** La pantalla ya disparó el PendingIntent. */
    fun onConsentLaunched() {
        consentShownAt = elapsed()
        _ui.update { it.copy(consent = null) }
        log.line("hoja de consentimiento lanzada")
    }

    fun onConsentLaunchFailed(t: Throwable) {
        _ui.update { it.copy(consent = null, authStatus = "no se pudo abrir la hoja") }
        log.error("lanzar la hoja de consentimiento", t)
    }

    /**
     * Vuelta de la hoja. Si la tarea que la pidió aún está cerrándose, se espera a que acabe en vez de descartar
     * el resultado (runTask rechaza tareas mientras hay otra en marcha).
     */
    fun onConsentResult(resultCode: Int, data: Intent?) {
        viewModelScope.launch {
            work?.join()
            handleConsentResult(resultCode, data)
        }
    }

    private fun handleConsentResult(resultCode: Int, data: Intent?) = runTask(TASK_CONSENT, autoConsent = false) {
        val shown = elapsed() - consentShownAt
        val code = when (resultCode) {
            Activity.RESULT_OK -> "RESULT_OK"
            Activity.RESULT_CANCELED -> "RESULT_CANCELED"
            else -> resultCode.toString()
        }
        log.line("hoja cerrada tras ${ProbeFormat.seconds(shown)}: $code, datos ${if (data == null) "null" else "presentes"}")
        val provider = tokenProvider()
        val granted = provider.completeConsent(data)
        log.line("scopes concedidos (${granted.size}): ${granted.joinToString(", ").ifEmpty { "ninguno" }}")
        val missing = provider.scopes.filterNot { it in granted }
        if (missing.isNotEmpty()) log.line("FALTAN scopes: ${missing.joinToString(", ")}")
        _ui.update { it.copy(grantedScopes = granted) }
        if (resultCode != Activity.RESULT_OK) {
            _ui.update { it.copy(authStatus = "consentimiento no completado ($code)") }
            return@runTask
        }
        val t0 = elapsed()
        val token = provider.accessToken()
        val ms = elapsed() - t0
        log.line("tras consentir: token obtenido (${token.length} caracteres) en $ms ms")
        onTokenOk("consentido; token en $ms ms")
    }

    /** Guarda la fecha del primer token (para la prueba de los 8 días) y actualiza el estado. */
    private fun onTokenOk(detail: String) {
        val stamp = sondaDir?.let { File(it, FIRST_TOKEN_FILE) }
        val info = try {
            when {
                stamp == null -> "sin carpeta de la sonda: no se guarda la fecha del primer token"
                !stamp.isFile -> {
                    stamp.parentFile?.mkdirs()
                    stamp.writeText(System.currentTimeMillis().toString())
                    "primer token: ahora (${fmtDate(System.currentTimeMillis())}); vuelve a probar en 8 días"
                }
                else -> {
                    val first = stamp.readText().trim().toLong()
                    "primer token: ${fmtDate(first)}, hace ${ProbeFormat.days(System.currentTimeMillis() - first)}"
                }
            }
        } catch (t: Throwable) {
            "no se pudo leer/guardar la fecha del primer token: ${SondaLog.describe(t)}"
        }
        log.line(info)
        _ui.update { it.copy(authStatus = "conectado ($detail)", firstTokenInfo = info) }
    }

    // ---------------------------------------------------------------- 3. Listar Cómics

    fun listComics() = runTask(TASK_LIST) {
        val api = driveApi()
        val about = timed { api.about() }
        val a = about.value
        log.line(
            "about: ${a.userEmail ?: "(sin correo)"} · usados ${a.usageBytes?.let(ProbeFormat::gb) ?: "?"} de " +
                "${a.limitBytes?.let(ProbeFormat::gb) ?: "sin límite"} (${about.ms} ms)",
        )

        val found = timed { api.findFolder(COMICS_FOLDER, "root") }
        var comics = found.value
        if (comics != null) {
            log.line("carpeta «${comics.name}»: id ${comics.id} (${found.ms} ms)")
        } else {
            log.line("findFolder(«$COMICS_FOLDER», root): no está (${found.ms} ms). Carpetas en Mi unidad:")
            val rootKids = timed { api.listChildren("root") }
            val folders = rootKids.value.filter { it.isFolder }
            folders.take(60).forEach { log.line("  [carpeta] ${it.name}") }
            if (folders.size > 60) log.line("  ... y ${folders.size - 60} más")
            log.line("root: ${rootKids.value.size} elementos, ${folders.size} carpetas (${rootKids.ms} ms)")
            comics = folders.firstOrNull { ProbeFormat.folderKey(it.name) == ProbeFormat.folderKey(COMICS_FOLDER) }
            if (comics == null) {
                log.line("ninguna carpeta coincide con «$COMICS_FOLDER» ni normalizando (NFC, espacios, mayúsculas)")
                return@runTask
            }
            log.line("se usa «${comics.name}» (coincide al normalizar): id ${comics.id}")
        }

        val first = timed { api.listChildren(comics.id) }
        val kids = first.value
        log.line("listChildren(Cómics): ${kids.size} elementos (${kids.count { it.isFolder }} carpetas) en ${first.ms} ms")
        kids.take(10).forEachIndexed { i, f ->
            val kind = if (f.isFolder) "[carpeta]" else "[fichero]"
            log.line("  ${i + 1}. $kind ${f.name}${f.size?.let { " · " + ProbeFormat.mb(it) } ?: ""}")
        }

        // Búsqueda en anchura de muestras: la carpeta «Cómics» cuenta como la primera de las 20.
        val candidates = mutableListOf<DriveFile>()
        val queue = ArrayDeque<DriveFile>()
        fun take(children: List<DriveFile>) {
            for (f in children) {
                if (f.isFolder) {
                    if (!f.name.startsWith(".")) queue.addLast(f)
                } else if (ProbeFormat.isSampleCandidate(f.name, f.isFolder, f.size)) {
                    candidates += f
                }
            }
        }
        val bfsStart = elapsed()
        var visited = 1
        take(kids)
        while (queue.isNotEmpty() && visited < MAX_FOLDERS && candidates.size < MAX_CANDIDATES) {
            val folder = queue.removeFirst()
            val r = timed { api.listChildren(folder.id) }
            visited++
            log.line("  carpeta $visited/$MAX_FOLDERS «${folder.name}»: ${r.value.size} elementos en ${r.ms} ms")
            take(r.value)
        }
        log.line(
            "búsqueda en anchura: $visited carpetas, ${candidates.size} candidatos (≤ 80 MB, .cbr/.cbz/.pdf) " +
                "en ${elapsed() - bfsStart} ms",
        )
        _ui.update { it.copy(candidates = candidates.toList()) }
        val sample = candidates.firstOrNull()
        if (sample == null) {
            log.line("sin muestra: no hay .cbr/.cbz/.pdf ≤ 80 MB en las primeras $visited carpetas")
        } else {
            useSample(sample)
        }
    }

    /** Toque en la lista de candidatos. */
    fun selectSample(file: DriveFile) {
        if (_ui.value.busy != null) {
            log.line("elige otra muestra cuando acabe «${_ui.value.busy}»")
            return
        }
        closeArchive()
        useSample(file)
    }

    private fun useSample(f: DriveFile) {
        val local = destFor(f).let { if (it.isFile) it.length() else 0L }
        _ui.update { it.copy(sample = f, localBytes = local, progress = null) }
        log.line(
            "muestra: «${f.name}» · id ${f.id} · ${f.size?.let(ProbeFormat::mb) ?: "?"} · ${f.mimeType} · " +
                "md5 ${if (f.md5Checksum != null) "sí" else "no"} · en disco ${ProbeFormat.mb(local)}",
        )
    }

    private fun destFor(f: DriveFile): File = File(workDir, "${f.id}.${ProbeFormat.sampleExtension(f.name) ?: "bin"}")

    // ---------------------------------------------------------------- 4. Descargar (Range)

    fun download() {
        val sample = _ui.value.sample ?: run {
            log.line("$TASK_DOWNLOAD: no hay muestra; primero «Listar Cómics»")
            return
        }
        runTask(TASK_DOWNLOAD) {
            val api = driveApi()
            val dest = destFor(sample)
            workDir.mkdirs()
            val size = sample.size
            var offset = if (dest.isFile) dest.length() else 0L
            if (size != null && offset == size) {
                log.line("ya está completo en disco ($offset bytes): no se descarga. «Borrar del disco» para repetir")
                return@runTask
            }
            if (size != null && offset > size) {
                log.line("en disco hay más bytes ($offset) que en Drive ($size): se borra y se empieza de cero")
                dest.delete()
                offset = 0L
            }
            log.line(
                if (offset > 0) "reanuda desde el byte $offset (${ProbeFormat.mb(offset)}) con Range: bytes=$offset-"
                else "descarga desde cero: «${sample.name}»",
            )
            _ui.update { it.copy(downloading = true, progress = DownloadProgress(offset, size, "— MB/s")) }
            val t0 = elapsed()
            var firstBytes = -1L
            var firstAt = 0L
            var lastUi = 0L
            try {
                api.download(sample.id, dest, resume = true) { bytes, total ->
                    val now = elapsed()
                    if (firstBytes < 0) {
                        firstBytes = bytes
                        firstAt = now
                    }
                    if (now - lastUi >= PROGRESS_EVERY_MS) {
                        lastUi = now
                        // Vale tanto si «bytes» cuenta lo de esta sesión como si es el acumulado en disco.
                        val onDisk = maxOf(bytes, dest.length())
                        val speed = ProbeFormat.mbPerSecond(bytes - firstBytes, now - firstAt)
                        _ui.update { it.copy(progress = DownloadProgress(onDisk, size ?: total, speed), localBytes = onDisk) }
                    }
                }
                val ms = elapsed() - t0
                val len = dest.length()
                val got = len - offset
                log.line(
                    "descarga terminada: ${ProbeFormat.mb(got)} nuevos en ${ProbeFormat.seconds(ms)} " +
                        "(${ProbeFormat.mbPerSecond(got, ms)}); en disco $len bytes",
                )
                when {
                    size == null -> log.line("Drive no dio tamaño: no se compara")
                    len == size -> log.line("tamaño coincide con Drive ($size bytes)")
                    else -> log.line("tamaño NO coincide: disco $len, Drive $size")
                }
                val expected = sample.md5Checksum
                if (expected == null) {
                    log.line("Drive no dio md5: no se compara")
                } else {
                    val m = timed { md5(dest) }
                    log.line(
                        if (m.value.equals(expected, ignoreCase = true)) "md5 coincide (${m.ms} ms)"
                        else "md5 NO coincide: disco ${m.value}, Drive $expected (${m.ms} ms)",
                    )
                }
            } catch (e: CancellationException) {
                val len = if (dest.isFile) dest.length() else 0L
                log.line("descarga cortada: en disco $len bytes (${ProbeFormat.mb(len)}) tras ${ProbeFormat.seconds(elapsed() - t0)}")
                throw e
            } finally {
                val len = if (dest.isFile) dest.length() else 0L
                _ui.update { it.copy(downloading = false, localBytes = len, progress = it.progress?.copy(bytes = len)) }
            }
        }
    }

    fun cut() {
        if (!_ui.value.downloading) return
        log.line("Cortar: cancelando la descarga")
        work?.cancel()
    }

    fun deleteLocal() {
        val sample = _ui.value.sample ?: return
        runTask(TASK_DELETE) {
            if (_ui.value.opened?.fileId == sample.id) closeArchive()
            val dest = destFor(sample)
            val had = if (dest.isFile) dest.length() else 0L
            dest.delete()
            File(workDir, "paginas/${sample.id}").deleteRecursively()
            File(workDir, "extraccion/${sample.id}").deleteRecursively()
            _ui.update { it.copy(localBytes = 0L, progress = null) }
            log.line("borrado del disco: ${ProbeFormat.mb(had)} de «${sample.name}» y sus páginas extraídas")
        }
    }

    // ---------------------------------------------------------------- 5. Abrir archivo

    fun openArchive() {
        val sample = _ui.value.sample ?: run {
            log.line("$TASK_OPEN: no hay muestra; primero «Listar Cómics»")
            return
        }
        runTask(TASK_OPEN) {
            val file = destFor(sample)
            if (!file.isFile || file.length() == 0L) {
                log.line("no hay fichero en disco: primero «Descargar»")
                return@runTask
            }
            val size = sample.size
            if (size != null && file.length() < size) {
                log.line("ojo: el fichero está incompleto (${file.length()} de $size bytes)")
            }
            closeArchive()

            val sniff = timed { ArchiveSniffer.sniff(file) }
            log.line("sniff: ${sniff.value} en ${sniff.ms} ms (extensión .${file.extension}, ${ProbeFormat.mb(file.length())})")

            val heapBefore = usedHeap()
            val opened = timed { ComicArchives.open(file) }
            val arch = opened.value
            // Se guarda ya para poder cerrarlo aunque falle lo que sigue.
            _ui.update { it.copy(opened = OpenedArchive(sample.id, sample.name, arch, "${arch.kind} (abriendo)")) }
            val n = arch.pageCount
            log.line("open: ${arch.kind}, $n páginas en ${opened.ms} ms")
            val shown = minOf(n, 12)
            if (shown > 0) {
                log.line("primeras $shown páginas en el orden del archivo:")
                for (i in 0 until shown) log.line("  ${i + 1}. ${arch.pageName(i)}")
            }

            val outDir = File(workDir, "extraccion/${sample.id}").apply {
                deleteRecursively()
                mkdirs()
            }
            for (i in ProbeFormat.probePages(n)) {
                try {
                    val name = arch.pageName(i)
                    val dest = PageFiles.fileFor(outDir, i, name)
                    val t = timed { synchronized(arch) { arch.extractPage(i, dest) } }
                    log.line("  extraer pág. ${i + 1}/$n «$name»: ${t.ms} ms, ${dest.length() / 1024} KB, ${imageInfo(dest)}")
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    log.error("  extraer pág. ${i + 1}/$n", t)
                }
            }
            log.line("memoria Java usada: ${ProbeFormat.mb(usedHeap())} (antes de abrir: ${ProbeFormat.mb(heapBefore)})")
            val summary = "${arch.kind} · $n páginas · abrir en ${opened.ms} ms"
            _ui.update { it.copy(opened = OpenedArchive(sample.id, sample.name, arch, summary)) }
        }
    }

    private fun closeArchive() {
        val current = _ui.value.opened ?: return
        _ui.update { it.copy(opened = null, reading = false) }
        try {
            synchronized(current.archive) { current.archive.close() }
            log.line("archivo cerrado: «${current.fileName}»")
        } catch (t: Throwable) {
            log.error("cerrar el archivo", t)
        }
    }

    // ---------------------------------------------------------------- 6. Leer

    fun setRtl(rtl: Boolean) {
        _ui.update { it.copy(rtl = rtl) }
        log.line("Manga RTL: ${if (rtl) "sí" else "no"}")
    }

    fun startReading() {
        val opened = _ui.value.opened ?: run {
            log.line("$TASK_READ: primero «Abrir archivo»")
            return
        }
        if (_ui.value.busy != null) {
            log.line("$TASK_READ espera: está en marcha «${_ui.value.busy}»")
            return
        }
        _ui.update { it.copy(reading = true) }
        log.line("lector abierto: «${opened.fileName}», ${opened.archive.pageCount} páginas, rtl=${_ui.value.rtl}")
    }

    fun stopReading() {
        _ui.update { it.copy(reading = false) }
        log.line("lector cerrado")
    }

    fun pageCacheDirFor(opened: OpenedArchive): File = File(workDir, "paginas/${opened.fileId}")

    // ---------------------------------------------------------------- 7. Registro

    fun copyLog() {
        try {
            val text = log.text()
            val cm = ctx.getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("sonda.log", text))
            log.line("registro copiado al portapapeles (${text.lines().size} líneas)")
        } catch (t: Throwable) {
            log.error("copiar el registro", t)
        }
    }

    val logPath: String? get() = log.file?.absolutePath

    // ---------------------------------------------------------------- utilidades

    private fun runTask(name: String, autoConsent: Boolean = true, block: suspend CoroutineScope.() -> Unit) {
        if (work?.isActive == true) {
            log.line("«$name» espera: está en marcha «${_ui.value.busy}»")
            return
        }
        work = viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(busy = name) }
            log.line("> $name")
            val t0 = elapsed()
            try {
                block()
            } catch (e: CancellationException) {
                log.line("$name: cancelado")
                throw e
            } catch (e: NeedsConsentException) {
                log.line("$name: hace falta consentimiento en pantalla")
                if (autoConsent) askConsent(e) else log.line("$name: no se relanza la hoja (evita un bucle); pulsa «$TASK_CONNECT»")
            } catch (e: DriveHttpException) {
                log.line("$name: ERROR HTTP ${e.code} ${e.reason ?: ""}: ${e.message}")
            } catch (t: Throwable) {
                log.error(name, t)
            } finally {
                _ui.update { it.copy(busy = null) }
                log.line("$name: fin (${elapsed() - t0} ms)")
            }
        }
    }

    private class Timed<T>(val value: T, val ms: Long)

    private inline fun <T> timed(block: () -> T): Timed<T> {
        val t0 = elapsed()
        val v = block()
        return Timed(v, elapsed() - t0)
    }

    private fun elapsed(): Long = SystemClock.elapsedRealtime()

    private fun usedHeap(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered(256 * 1024).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Dimensiones y tipo leyendo solo la cabecera de la imagen (no decodifica los píxeles). */
    private fun imageInfo(file: File): String = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, o)
        if (o.outWidth > 0) "${o.outWidth}x${o.outHeight} ${o.outMimeType ?: "?"}" else "no es una imagen legible"
    } catch (t: Throwable) {
        SondaLog.describe(t)
    }

    private fun fmtDate(epochMs: Long): String =
        DATE.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))

    override fun onCleared() {
        watcher.stop()
        closeArchive()
        super.onCleared()
    }

    companion object {
        const val COMICS_FOLDER = "Cómics"
        const val MAX_FOLDERS = 20
        const val MAX_CANDIDATES = 50
        private const val PROGRESS_EVERY_MS = 250L
        private const val FIRST_TOKEN_FILE = "primer-token.txt"
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        const val TASK_DEVICE = "Dispositivo"
        const val TASK_CONNECT = "Conectar Drive"
        const val TASK_SILENT = "Token silencioso"
        const val TASK_FORCE = "Token nuevo (forceRefresh)"
        const val TASK_CONSENT = "Resultado del consentimiento"
        const val TASK_LIST = "Listar Cómics"
        const val TASK_DOWNLOAD = "Descargar (Range)"
        const val TASK_DELETE = "Borrar del disco"
        const val TASK_OPEN = "Abrir archivo"
        const val TASK_READ = "Leer"
    }
}
