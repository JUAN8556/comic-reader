package mx.jano.comics.archive

import com.github.junrar.Archive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.text.Normalizer
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random

/**
 * Informe con cómics REALES de la Mac. Se salta si no existe COMICS_SAMPLES (fichero con una ruta por línea).
 *
 *   COMICS_SAMPLES=/ruta/lista.txt  COMICS_TMP=/carpeta/temporal  COMICS_REPORT=/ruta/informe.md \
 *     ./gradlew :app:testDebugUnitTest --tests '*LocalComicsReportTest*' --rerun
 *
 * Solo lee los ficheros de la lista (nada más de la biblioteca). Las páginas se extraen a COMICS_TMP y se borran.
 */
class LocalComicsReportTest {

    private class Row(val index: Int, val file: File) {
        var label = ""
        var ext = ""
        var sizeMb = 0.0
        var sniff: ArchiveKind? = null
        var kind: ArchiveKind? = null
        var junrarMainSolid: Boolean? = null
        var solid: Boolean? = null
        var method: String = ""
        var windowKb: Long? = null
        var broken = 0
        var pages: Int? = null
        var openMs: Double? = null
        var p0Ms: Double? = null
        var midMs: Double? = null
        var midReadMb: Double? = null
        var lastMs: Double? = null
        var lastReadMb: Double? = null
        var backMs: Double? = null
        var backReadMb: Double? = null
        var seqMs: Double? = null
        var seqMb: Double? = null
        var directPages: Int? = null
        var error: String? = null
        var timeout = false
    }

    private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    @Test fun informe_de_archivos_locales() {
        val listPath = env("COMICS_SAMPLES")
        assumeTrue("COMICS_SAMPLES no está definida: informe saltado", listPath != null)
        val paths = File(listPath!!).readLines(Charsets.UTF_8).map { it.trimEnd('\r') }.filter { it.isNotBlank() }
        val work = File(env("COMICS_TMP") ?: System.getProperty("java.io.tmpdir"), "informe-${System.currentTimeMillis()}")
        work.mkdirs()
        val reportFile = File(env("COMICS_REPORT") ?: "build/informe-archivos-locales.md")
        val timeoutS = env("COMICS_TIMEOUT_S")?.toLong() ?: 60L

        // El sólido sintético va primero: además calienta el JIT de junrar antes de medir los reales.
        val synthetic = syntheticSolidBenchmark(File(work, "sintetico"))
        val rows = paths.mapIndexed { i, p ->
            val row = Row(i + 1, File(p))
            measureWithTimeout(row, File(work, "f${i + 1}"), timeoutS)
            println(
                String.format(
                    Locale.ROOT, "[%3d/%d] %-10s %4s págs %s %s", i + 1, paths.size, row.kind ?: row.sniff,
                    row.pages ?: "-", row.label, row.error ?: "",
                ),
            )
            row
        }
        work.deleteRecursively()
        reportFile.absoluteFile.parentFile?.mkdirs()
        reportFile.writeText(render(rows, synthetic, timeoutS), Charsets.UTF_8)
        println("Informe: ${reportFile.absolutePath}")
    }

    // ---------------------------------------------------------------- medición por fichero

    private fun measureWithTimeout(row: Row, dir: File, timeoutS: Long) {
        row.label = displayName(row.file)
        row.ext = row.file.extension.lowercase(Locale.ROOT)
        row.sizeMb = row.file.length() / 1e6
        val opened = Collections.synchronizedList(mutableListOf<ComicArchive>())
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "informe-${row.index}").apply { isDaemon = true } }
        val future = pool.submit { measure(row, dir, opened) }
        try {
            future.get(timeoutS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            row.timeout = true
            row.error = "TIEMPO AGOTADO (> $timeoutS s)"
            synchronized(opened) { opened.forEach { runCatching { it.close() } } }
            future.cancel(true)
        } catch (e: ExecutionException) {
            val c = e.cause ?: e
            row.error = "${c.javaClass.simpleName}: ${c.message}"
        } finally {
            pool.shutdownNow()
            dir.deleteRecursively()
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1e6

    private fun measure(row: Row, dir: File, opened: MutableList<ComicArchive>) {
        dir.mkdirs()
        val f = row.file
        row.sniff = ArchiveSniffer.sniff(f)
        fun open(): ComicArchive = ComicArchives.open(f).also { opened.add(it) }
        fun readMb(a: ComicArchive, before: Long) = (a as? RarComicArchive)?.let { (it.packedBytesRead - before) / 1e6 }
        fun read(a: ComicArchive) = (a as? RarComicArchive)?.packedBytesRead ?: 0L
        try {
            // 1) Abrir + página 0 (en frío)
            var t = System.nanoTime()
            val a1 = open()
            row.openMs = ms(t)
            row.kind = a1.kind
            row.pages = a1.pageCount
            (a1 as? RarComicArchive)?.let {
                row.junrarMainSolid = it.mainHeaderSolid
                row.solid = it.isSolid
                row.method = it.firstPageMethod?.let { m -> "0x" + m.toString(16) } ?: ""
                row.windowKb = it.maxWindowBytes / 1024
                row.broken = it.brokenHeaders
            }
            (a1 as? ZipComicArchive)?.let { row.method = it.firstPageMethod?.toString() ?: "" }
            val n = a1.pageCount
            if (n == 0) {
                a1.close(); row.error = "sin páginas de imagen"; return
            }
            t = System.nanoTime()
            a1.extractPage(0, File(dir, "a0"))
            row.p0Ms = ms(t)
            a1.close()

            // 2) Página n/2 en frío (abrir otra vez y pedir solo esa)
            val mid = n / 2
            val a2 = open()
            var before = read(a2)
            t = System.nanoTime()
            a2.extractPage(mid, File(dir, "b$mid"))
            row.midMs = ms(t)
            row.midReadMb = readMb(a2, before)
            a2.close()

            // 3) Última en frío y luego un paso atrás (n-2) en la misma instancia
            val a3 = open()
            before = read(a3)
            t = System.nanoTime()
            a3.extractPage(n - 1, File(dir, "c${n - 1}"))
            row.lastMs = ms(t)
            row.lastReadMb = readMb(a3, before)
            if (n >= 2) {
                before = read(a3)
                t = System.nanoTime()
                a3.extractPage(n - 2, File(dir, "c${n - 2}"))
                row.backMs = ms(t)
                row.backReadMb = readMb(a3, before)
            }
            a3.close()

            // 4) Todas en orden (instancia nueva), borrando cada página tras escribirla
            val a4 = open()
            var bytes = 0L
            t = System.nanoTime()
            for (i in 0 until n) {
                val d = File(dir, "s")
                a4.extractPage(i, d)
                bytes += d.length()
                d.delete()
            }
            row.seqMs = ms(t)
            row.seqMb = bytes / 1e6
            row.directPages = (a4 as? RarComicArchive)?.storedDirectReads
            a4.close()
        } catch (e: Throwable) {
            if (!row.timeout) row.error = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            synchronized(opened) { opened.forEach { runCatching { it.close() } } }
        }
    }

    private fun displayName(f: File): String {
        val p = Normalizer.normalize(f.path, Normalizer.Form.NFC)
        val marker = "/Cómics /"
        val i = p.indexOf(marker)
        return if (i >= 0) p.substring(i + marker.length) else f.name
    }

    // ---------------------------------------------------------------- sólido sintético

    private class Synthetic(
        val pages: Int,
        val pageMb: Double,
        val lines: List<String>,
    )

    /**
     * No hay ni un RAR4 sólido entre los ficheros locales, así que se mide el comportamiento de junrar con un RAR4
     * sólido escrito en la prueba (entradas guardadas: el coste es de lectura y CRC, no de descompresión).
     * Lo que importa es cuánto RELEE junrar: bytes comprimidos leídos por patrón de lectura.
     */
    private fun syntheticSolidBenchmark(dir: File): Synthetic {
        dir.mkdirs()
        val n = 60
        val size = 1_000_000
        val entries = (1..n).map { Rar4TestWriter.Entry(String.format(Locale.ROOT, "%03d.jpg", it), Random(it).nextBytes(size)) }
        val solidFile = File(dir, "solido.cbr").also { Rar4TestWriter.write(it, entries, solid = true) }
        val plainFile = File(dir, "plano.cbr").also { Rar4TestWriter.write(it, entries, solid = false) }
        val lines = mutableListOf<String>()

        fun rawPattern(file: File, order: List<Int>): Pair<Double, Double> {
            val progress = RarComicArchive.PackedProgress()
            val t = System.nanoTime()
            Archive(file, com.github.junrar.ArchiveOptions.builder().unrarCallback(progress).build()).use { ar ->
                val h = ar.fileHeaders
                val sink = File(dir, "raw")
                for (i in order) sink.outputStream().buffered().use { ar.extractFile(h[i], it) }
            }
            return ms(t) to progress.packedRead / 1e6
        }

        fun oursPattern(file: File, order: List<Int>): Pair<Double, Double> {
            val pagesDir = File(dir, "p").apply { deleteRecursively(); mkdirs() }
            val t = System.nanoTime()
            val a = ComicArchives.open(file) as RarComicArchive
            a.use { for (i in order) it.extractPage(i, File(pagesDir, "$i")) }
            return ms(t) to a.packedBytesRead / 1e6
        }

        val patterns = listOf(
            "adelante 0→${n - 1}" to (0 until n).toList(),
            "atrás ${n - 1}→0" to (n - 1 downTo 0).toList(),
            "última y luego la primera" to listOf(n - 1, 0),
            "saltos (0, ${n / 2}, 5, ${n - 1}, ${n / 2 - 1})" to listOf(0, n / 2, 5, n - 1, n / 2 - 1),
        )
        lines += "| Patrón | junrar a pelo, sólido | con caché, sólido | junrar, no sólido |"
        lines += "|---|---|---|---|"
        for ((label, order) in patterns) {
            val raw = rawPattern(solidFile, order)
            val ours = oursPattern(solidFile, order)
            val plain = rawPattern(plainFile, order)
            lines += String.format(
                Locale.ROOT, "| %s | %.0f ms · %.0f MB leídos | %.0f ms · %.0f MB leídos | %.0f ms · %.0f MB leídos |",
                label, raw.first, raw.second, ours.first, ours.second, plain.first, plain.second,
            )
        }
        dir.deleteRecursively()
        return Synthetic(n, size / 1e6, lines)
    }

    // ---------------------------------------------------------------- informe

    private fun f0(v: Double?) = v?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—"
    private fun f1(v: Double?) = v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"
    private fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")

    private fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return s[((s.size - 1) * p).toInt()]
    }

    private fun render(rows: List<Row>, syn: Synthetic, timeoutS: Long): String = buildString {
        val ok = rows.filter { it.error == null }
        val failed = rows.filter { it.error != null }
        appendLine("# Informe: archivos locales con el motor de la sonda")
        appendLine()
        appendLine("Generado por `LocalComicsReportTest` (rama `sonda/archive`) el ${java.time.LocalDate.now()}.")
        appendLine(
            "Máquina: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}, " +
                "${Runtime.getRuntime().availableProcessors()} núcleos, Java ${System.getProperty("java.version")}, " +
                "heap máx. ${Runtime.getRuntime().maxMemory() shr 20} MB. junrar 8.1.2 es Java puro: en el Helio G100 irá " +
                "varias veces más lento, pero las proporciones valen.",
        )
        appendLine()
        appendLine("Ficheros: ${rows.size} (la lista de los que están físicamente en la Mac). Tiempo máximo por fichero: $timeoutS s.")
        appendLine()
        appendLine("## Resumen")
        appendLine()
        appendLine("| Formato real (cabecera) | Ficheros | .cbr | .cbz | Abren bien | Fallan |")
        appendLine("|---|---|---|---|---|---|")
        for (k in ArchiveKind.entries) {
            val of = rows.filter { it.sniff == k }
            if (of.isEmpty()) continue
            appendLine(
                "| $k | ${of.size} | ${of.count { it.ext == "cbr" }} | ${of.count { it.ext == "cbz" }} | " +
                    "${of.count { it.error == null }} | ${of.count { it.error != null }} |",
            )
        }
        val noSniff = rows.filter { it.sniff == null }
        if (noSniff.isNotEmpty()) appendLine("| (no se pudo leer) | ${noSniff.size} | | | 0 | ${noSniff.size} |")
        appendLine()
        val disguised = rows.filter { (it.ext == "cbr" && it.sniff == ArchiveKind.ZIP) || (it.ext == "cbz" && it.sniff != ArchiveKind.ZIP) || it.sniff == ArchiveKind.SEVEN_ZIP }
        appendLine("- Disfrazados (la extensión miente): ${disguised.size} — " + disguised.joinToString("; ") { "${it.label} → ${it.sniff}" }.ifEmpty { "ninguno" })
        val solidRows = rows.filter { it.solid == true || it.sniff == ArchiveKind.RAR4_SOLID }
        appendLine("- Sólidos: ${solidRows.size} (sniffer RAR4_SOLID: ${rows.count { it.sniff == ArchiveKind.RAR4_SOLID }}; junrar `isSolid`: ${rows.count { it.solid == true }}).")
        val mismatch = rows.filter { r ->
            r.junrarMainSolid != null && r.sniff in setOf(ArchiveKind.RAR4, ArchiveKind.RAR4_SOLID) &&
                (r.sniff == ArchiveKind.RAR4_SOLID) != r.junrarMainSolid
        }
        appendLine("- Sniffer (byte 10 & 0x08) contra junrar `getMainHeader().isSolid()`: ${rows.count { it.junrarMainSolid != null }} RAR4 comparados, ${mismatch.size} discrepancias.")
        val methods = rows.filter { it.method.isNotEmpty() }.groupingBy { "${it.kind}:${it.method}" }.eachCount().toSortedMap()
        appendLine("- Método de compresión de la primera página (RAR4 0x30 guardado … 0x35 máxima; RAR5 0-5; ZIP 0 guardado / 8 deflate): " + methods.entries.joinToString(", ") { "${it.key} × ${it.value}" })
        val windows = rows.filter { it.windowKb != null }.groupingBy { "${it.kind}:${it.windowKb} KB" }.eachCount().toSortedMap()
        appendLine("- Ventana (diccionario) máxima: " + windows.entries.joinToString(", ") { "${it.key} × ${it.value}" })
        appendLine("- Cabeceras rotas toleradas por junrar: ${rows.sumOf { it.broken }} en ${rows.count { it.broken > 0 }} ficheros.")
        val direct = rows.filter { (it.directPages ?: 0) > 0 }
        appendLine(
            "- Páginas GUARDADAS leídas directamente (sin junrar, con CRC32): ${direct.sumOf { it.directPages ?: 0 }} " +
                "en ${direct.size} RAR (de ${rows.count { it.directPages != null }} RAR que llegaron a «todo en orden»).",
        )
        appendLine()
        appendLine("### Fallos")
        appendLine()
        if (failed.isEmpty()) appendLine("Ninguno.")
        for (r in failed) appendLine("- #${r.index} ${cell(r.label)} (${r.ext}, cabecera ${r.sniff}): ${cell(r.error!!)}")
        appendLine()
        appendLine("### Tiempos (los que abren bien)")
        appendLine()
        appendLine("| Formato | n | abrir p50 / p95 ms | p0 p50 / p95 | p(n/2) p50 / p95 | última p50 / p95 | atrás p50 / p95 | todo en orden p50 MB/s (mín.) |")
        appendLine("|---|---|---|---|---|---|---|---|")
        for (k in ArchiveKind.entries) {
            val of = ok.filter { it.kind == k }
            if (of.isEmpty()) continue
            fun pp(sel: (Row) -> Double?): String {
                val v = of.mapNotNull(sel)
                return "${f0(percentile(v, 0.5))} / ${f0(percentile(v, 0.95))}"
            }
            val speeds = of.mapNotNull { r -> if (r.seqMs != null && r.seqMb != null && r.seqMs!! > 0) r.seqMb!! / (r.seqMs!! / 1000) else null }
            appendLine(
                "| $k | ${of.size} | ${pp { it.openMs }} | ${pp { it.p0Ms }} | ${pp { it.midMs }} | ${pp { it.lastMs }} | ${pp { it.backMs }} | " +
                    "${f0(percentile(speeds, 0.5))} (${f0(speeds.minOrNull())}) |",
            )
        }
        appendLine()
        appendLine("## Sólidos: qué hace junrar 8.1.2 y la mitigación")
        appendLine()
        appendLine(
            "Leído en el bytecode (`javap -c com.github.junrar.Archive`, `extractFile`): en un sólido junrar recuerda la última " +
                "entrada descomprimida (`lastProcessedFileIndex`). Si se pide una posterior, descomprime las de en medio a un " +
                "`NullOutputStream`; si se pide una anterior o la misma, llama a `Unpack.init(null)` y vuelve a empezar desde la " +
                "primera entrada. El «acceso aleatorio en sólidos» de 7.6.0 es correcto pero cuesta O(k) por página: pasar páginas " +
                "hacia atrás (o en un orden natural distinto del físico) es O(n²).",
        )
        appendLine()
        appendLine(
            "Como entre los ${rows.size} ficheros locales no hay ningún RAR sólido, se midió con un RAR4 sólido escrito por la prueba " +
                "(${syn.pages} páginas de ${f1(syn.pageMb)} MB, entradas guardadas). MB leídos = bytes comprimidos que pasa junrar " +
                "(contados con `UnrarCallback.volumeProgressChanged`), independiente de la máquina:",
        )
        appendLine()
        syn.lines.forEach { appendLine(it) }
        appendLine()
        appendLine("## Tabla por fichero")
        appendLine()
        appendLine("Tiempos en ms. «p0», «p(n/2)» y «última» = abrir de nuevo y extraer solo esa página (lo que se espera al retomar ahí); «abrir» es del primer intento (incluye la lectura en frío de las cabeceras). «atrás» = la penúltima justo después de la última, en la misma instancia. «MB leídos» = comprimidos que leyó junrar para esa página. «todo» = las n páginas en orden con una instancia nueva.")
        appendLine()
        appendLine("| # | Fichero | Ext | MB | Cabecera | Tipo | Sólido | Mét. | Págs | Abrir | p0 | p(n/2) | MB leídos | Última | MB leídos | Atrás | MB leídos | Todo (s) | MB/s | Resultado |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for (r in rows) {
            val speed = if (r.seqMs != null && r.seqMb != null && r.seqMs!! > 0) r.seqMb!! / (r.seqMs!! / 1000) else null
            appendLine(
                "| ${r.index} | ${cell(r.label)} | ${r.ext} | ${f1(r.sizeMb)} | ${r.sniff ?: "—"} | ${r.kind ?: "—"} | " +
                    "${r.solid?.let { if (it) "sí" else "no" } ?: "—"} | ${r.method.ifEmpty { "—" }} | ${r.pages ?: "—"} | " +
                    "${f0(r.openMs)} | ${f0(r.p0Ms)} | ${f0(r.midMs)} | ${f1(r.midReadMb)} | ${f0(r.lastMs)} | ${f1(r.lastReadMb)} | " +
                    "${f0(r.backMs)} | ${f1(r.backReadMb)} | ${f1(r.seqMs?.div(1000))} | ${f0(speed)} | ${cell(r.error ?: "ok")} |",
            )
        }
    }
}
