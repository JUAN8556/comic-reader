package mx.jano.comics.metadata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.jano.comics.drive.DriveAbout
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.library.Book
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer

class MetadataRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    /** Drive con Cómics/.indice/ en memoria; [files] nombre → (md5, contenido). */
    private class FakeDrive : DriveApi {
        val files = LinkedHashMap<String, Pair<String, String>>()
        val downloads = mutableListOf<String>()
        var hasIndice = true
        override suspend fun findFolder(name: String, parentId: String): DriveFile? = null
        override suspend fun listChildren(folderId: String): List<DriveFile> = when (folderId) {
            "ROOT" -> if (hasIndice) listOf(DriveFile("IDX", Normalizer.normalize(".indice", Normalizer.Form.NFD), "application/vnd.google-apps.folder")) else emptyList()
            "IDX" -> files.map { (name, v) -> DriveFile("id-$name", name, "application/json", v.second.length.toLong(), v.first) }
            else -> emptyList()
        }
        override suspend fun download(fileId: String, dest: File, resume: Boolean, onProgress: (Long, Long?) -> Unit) {
            val name = fileId.removePrefix("id-")
            downloads += name
            dest.parentFile?.mkdirs()
            dest.writeText(files.getValue(name).second)
        }
        override suspend fun about() = DriveAbout()
    }

    private fun repo(drive: DriveApi, root: String? = "ROOT"): DriveMetadataRepository {
        val dir = File(tmp.root, "files/indice")
        return DriveMetadataRepository(dir, IndiceSync(dir, drive, rootId = { root }, log = { println("[índice] $it") }), scope, log = { println("[metadatos] $it") })
    }

    private fun book(path: String, md5: String? = null, size: Long = 100) =
        Book(id = path, name = path.substringAfterLast('/'), folderId = "F", path = path, size = size, md5 = md5)

    private val indice = """
        {"ruta":"Manga/Naruto/Naruto - Tomo 54 (#505-514).cbr","md5":"AAA","size":5000,"tipo":"RAR4","paginas":190,"numero":"54","campo_nuevo":true}
        esto no es json
        {"ruta":"Manga/Naruto/Naruto - Tomo 6.cbr","md5":"bbb","size":"6000","paginas":"180","numero":6}

        {"sin_ruta":1}
        {"ruta":"Americano/Marvel/Marvel 2099 01.cbr","md5":"ccc","size":10,"anio":"1992","paginas":"muchas"}
        {"ruta":"${Normalizer.normalize("Europeo/Astérix/Astérix 01.cbz", Normalizer.Form.NFD)}","md5":"ddd","size":20}
    """.trimIndent()

    private val series = """
        {"ruta":"Manga/Naruto","titulo":"Naruto","numeros_totales":72,"guion":"Masashi Kishimoto","dibujo":["Masashi Kishimoto"],"confianza":"alta"}
        {"ruta":"Americano/Marvel","titulo":"Marvel","generos":["superhéroes"]}
        {"ruta":"Americano/Marvel/Marvel 2099","titulo":"Marvel 2099","numeros_totales":"5","anios":1992}
        [1,2,3]
    """.trimIndent()

    @Test fun baja_parsea_tolerante_y_responde() = runBlocking {
        val drive = FakeDrive().apply {
            files["indice.jsonl"] = "m1" to indice
            files["series.jsonl"] = "m2" to series
            files["taxonomia.json"] = "m3" to """{"categorias":["Americano","Europeo","Latinoamericano","Manga","Manhwa","Manhua"]}"""
            files["portadas.zip"] = "m4" to "no se baja aquí"
        }
        val r = repo(drive)
        assertFalse(r.loaded)
        assertTrue(r.refreshFromDrive().isSuccess)
        assertTrue(r.loaded)
        assertEquals(listOf("indice.jsonl", "series.jsonl", "taxonomia.json"), drive.downloads)
        assertEquals(4, r.entryCount)
        assertEquals(3, r.seriesCount)
        assertEquals(3, r.skippedLines) // «esto no es json», la de sin ruta y [1,2,3]; las vacías no cuentan
        assertNotNull(r.taxonomy)

        // Por ruta, aunque el libro venga en NFD o el índice en NFD.
        val e54 = r.entryFor(book("Manga/Naruto/Naruto - Tomo 54 (#505-514).cbr"))!!
        assertEquals(190, e54.paginas)
        assertEquals("54", e54.numero)
        val e6 = r.entryFor(book(Normalizer.normalize("Manga/Naruto/Naruto - Tomo 6.cbr", Normalizer.Form.NFD)))!!
        assertEquals(6000L, e6.size)                // «"6000"» con comillas
        assertEquals(180, e6.paginas)
        assertEquals("6", e6.numero)                // 6 sin comillas en un campo de texto
        val e2099 = r.entryFor(book("Americano/Marvel/Marvel 2099 01.cbr"))!!
        assertEquals(1992, e2099.anio)
        assertNull(e2099.paginas)                   // «muchas»: se descarta el campo, no la línea
        assertNotNull(r.entryFor(book("Europeo/Astérix/Astérix 01.cbz")))

        // Por md5 si se movió o renombró (prefiriendo el mismo tamaño).
        assertEquals("54", r.entryFor(book("Otra/Carpeta/renombrado.cbr", md5 = "aaa", size = 5000))?.numero)
        assertNull(r.entryFor(book("Otra/nada.cbr", md5 = "zzz")))

        // Ficha: exacta o la más cercana hacia arriba.
        val naruto = r.seriesFor("Manga/Naruto")!!
        assertEquals(listOf("Masashi Kishimoto"), naruto.guion) // cadena → lista
        assertEquals("alta", naruto.confianza)
        assertEquals("Naruto", r.seriesFor("Manga/Naruto/Extras")?.titulo)
        assertEquals("Marvel", r.seriesFor("Americano/Marvel/Spider-Man")?.titulo)
        assertEquals(5, r.seriesFor("Americano/Marvel/Marvel 2099")?.numeros_totales)
        assertEquals("1992", r.seriesFor("Americano/Marvel/Marvel 2099")?.anios)
        assertNull(r.seriesFor("Europeo"))
    }

    @Test fun solo_baja_lo_que_cambia_y_funciona_sin_conexion() = runBlocking {
        val drive = FakeDrive().apply {
            files["indice.jsonl"] = "m1" to indice
            files["series.jsonl"] = "m2" to series
        }
        val r = repo(drive)
        r.refreshFromDrive().getOrThrow()
        r.refreshFromDrive().getOrThrow()
        assertEquals(listOf("indice.jsonl", "series.jsonl"), drive.downloads)

        drive.files["series.jsonl"] = "m2-v2" to """{"ruta":"Manga/Naruto","titulo":"NARUTO v2"}"""
        r.refreshFromDrive().getOrThrow()
        assertEquals(listOf("indice.jsonl", "series.jsonl", "series.jsonl"), drive.downloads)
        assertEquals("NARUTO v2", r.seriesFor("Manga/Naruto")?.titulo)

        // Otro arranque sin red: carga lo último de disco.
        val offline = object : DriveApi by drive {
            override suspend fun listChildren(folderId: String): List<DriveFile> = throw java.net.UnknownHostException("sin red")
        }
        val again = repo(offline)
        again.awaitInitialLoad()
        assertTrue(again.loaded)
        assertEquals("NARUTO v2", again.seriesFor("Manga/Naruto")?.titulo)
        assertTrue(again.refreshFromDrive().isFailure)
        assertEquals("NARUTO v2", again.seriesFor("Manga/Naruto")?.titulo)
    }

    @Test fun sin_indice_ni_raiz_es_silencioso() = runBlocking {
        val drive = FakeDrive().apply { hasIndice = false }
        val r = repo(drive)
        assertTrue(r.refreshFromDrive().isSuccess)
        assertFalse(r.loaded)
        assertNull(r.entryFor(book("a/b.cbr")))
        assertNull(r.seriesFor("a"))
        assertTrue(repo(FakeDrive(), root = null).refreshFromDrive().isSuccess)
    }

    @Test fun un_fichero_que_desaparece_de_drive_se_olvida() = runBlocking {
        val drive = FakeDrive().apply {
            files["indice.jsonl"] = "m1" to indice
            files["series.jsonl"] = "m2" to series
        }
        val r = repo(drive)
        r.refreshFromDrive().getOrThrow()
        drive.files.remove("series.jsonl")
        r.refreshFromDrive().getOrThrow()
        assertNull(r.seriesFor("Manga/Naruto"))
        assertNotNull(r.entryFor(book("Manga/Naruto/Naruto - Tomo 6.cbr")))
    }

    @Test fun recarga_si_otro_bajo_el_indice() = runBlocking {
        val drive = FakeDrive().apply { files["indice.jsonl"] = "m1" to """{"ruta":"a/1.cbr","numero":"1"}""" }
        val dir = File(tmp.root, "files/indice")
        val r = repo(drive)
        r.refreshFromDrive().getOrThrow()
        assertEquals("1", r.entryFor(book("a/1.cbr"))?.numero)
        // Las portadas (otro IndiceSync sobre la misma carpeta) bajan un indice.jsonl nuevo.
        drive.files["indice.jsonl"] = "m2" to """{"ruta":"a/1.cbr","numero":"uno-bis"}""" + "\n" + """{"ruta":"a/2.cbr"}"""
        IndiceSync(dir, drive, rootId = { "ROOT" }).sync(setOf(IndiceSync.INDICE))
        r.refreshFromDrive().getOrThrow()
        assertEquals("uno-bis", r.entryFor(book("a/1.cbr"))?.numero)
        assertNotNull(r.entryFor(book("a/2.cbr")))
    }

    @Test fun numeros_que_faltan() = runBlocking {
        val drive = FakeDrive().apply {
            files["indice.jsonl"] = "m1" to indice
            files["series.jsonl"] = "m2" to series
        }
        val r = repo(drive)
        r.refreshFromDrive().getOrThrow()
        val folder = "Americano/Marvel/Marvel 2099"
        val books = listOf(
            book("$folder/Marvel 2099 01.cbr"),
            book("$folder/Marvel 2099 03.cbr"),
            book("$folder/Marvel 2099 #05.cbr"),
        )
        assertEquals(listOf(2, 4), r.missingNumbers(folder, books))
        // El número del índice manda sobre el del nombre.
        val naruto = listOf(book("Manga/Naruto/Naruto - Tomo 54 (#505-514).cbr"), book("Manga/Naruto/Naruto - Tomo 6.cbr"))
        val missing = r.missingNumbers("Manga/Naruto", naruto)
        assertEquals(70, missing.size)
        assertFalse(54 in missing)
        assertFalse(6 in missing)
        assertFalse(505 in missing)
        // Sin ficha exacta, sin total, sin libros o sin números: nada.
        assertTrue(r.missingNumbers("Manga/Naruto/Extras", naruto).isEmpty())
        assertTrue(r.missingNumbers("Americano/Marvel", books).isEmpty())
        assertTrue(r.missingNumbers(folder, emptyList()).isEmpty())
        assertTrue(r.missingNumbers(folder, listOf(book("$folder/Especial.cbr"))).isEmpty())
    }

    /** series.jsonl de la migración lleva la ruta de ORIGEN (iCloud); la ficha llega a Drive por `meta_serie`. */
    @Test fun ficha_desde_meta_serie_del_indice() = runBlocking {
        val drive = FakeDrive().apply {
            files["indice.jsonl"] = "m1" to """
                {"ruta":"Americano/Marvel/Hulk - La Caída del Panteón/Hulk - La Caída del Panteón 01.cbr","numero":"01","meta_serie":{"titulo":"Hulk: La caída del Panteón","numeros_totales":4,"guion":"Guionista Uno"}}
                {"ruta":"Americano/Marvel/Hulk - La Caída del Panteón/Hulk - La Caída del Panteón 03.cbr","numero":"03","meta_serie":{"titulo":"otra línea de la misma carpeta: no manda"}}
                {"ruta":"Manga/Naruto/Naruto - Tomo 6.cbr","numero":"6","meta_serie":{"titulo":"Naruto (índice)","numeros_totales":"mal"}}
                {"ruta":"suelto.cbr","meta_serie":{"titulo":"en la raíz: sin carpeta"}}
                {"ruta":"Manga/Roto/1.cbr","meta_serie":"no es un objeto"}
            """.trimIndent()
            // La ruta de series.jsonl es la de iCloud: no casa con ninguna carpeta de Drive salvo Naruto.
            files["series.jsonl"] = "m2" to """
                {"ruta":"Marvel/Hulk La Caida Del Panteon (Forum)","titulo":"Hulk: La caída del Panteón"}
                {"ruta":"Manga/Naruto","titulo":"Naruto","numeros_totales":72}
            """.trimIndent()
        }
        val r = repo(drive)
        r.refreshFromDrive().getOrThrow()
        val folder = "Americano/Marvel/Hulk - La Caída del Panteón"
        val hulk = r.seriesFor(folder)!!
        assertEquals("Hulk: La caída del Panteón", hulk.titulo)
        assertEquals(listOf("Guionista Uno"), hulk.guion)
        assertEquals(4, hulk.numeros_totales)
        val books = listOf(book("$folder/Hulk - La Caída del Panteón 01.cbr"), book("$folder/Hulk - La Caída del Panteón 03.cbr"))
        assertEquals(listOf(2, 4), r.missingNumbers(folder, books))
        // Si series.jsonl casa con la carpeta de Drive, gana sobre la del índice.
        assertEquals("Naruto", r.seriesFor("Manga/Naruto")?.titulo)
        assertEquals(72, r.seriesFor("Manga/Naruto")?.numeros_totales)
        assertNull(r.seriesFor("Manga/Roto"))
        assertNull(r.seriesFor(""))
        assertEquals(3, r.seriesCount) // carpetas con ficha: Hulk y Naruto de Drive, y la de iCloud que no casa con nada
    }

    @Test fun lineas_tolerantes_puras() {
        assertNull(TolerantJson.decodeLine("{roto", IndexEntry.serializer()))
        assertNull(TolerantJson.decodeLine("""{"md5":"x"}""", IndexEntry.serializer()))
        // Modo permisivo: un número sin comillas vale como texto.
        assertEquals("5", TolerantJson.decodeLine("""{"ruta":5}""", IndexEntry.serializer())?.ruta)
        assertNull(TolerantJson.decodeLine("""{"ruta":["a"]}""", IndexEntry.serializer()))
        val e = TolerantJson.decodeLine("""{"ruta":"a","paginas":[12],"serie":null,"tipo":"ZIP"}""", IndexEntry.serializer())!!
        assertEquals(12, e.paginas)
        assertEquals("ZIP", e.tipo)
        val parsed = TolerantJson.parseLines("\uFEFF{\"ruta\":\"x\"}\n\n{\"ruta\":\"y\"}\r\nbasura", IndexEntry.serializer())
        assertEquals(listOf("x", "y"), parsed.items.map { it.ruta })
        assertEquals(1, parsed.skipped)
        assertEquals(12, TolerantJson.firstInt("012.5"))
        assertEquals(7, TolerantJson.firstInt("#7"))
        assertNull(TolerantJson.firstInt("sin número"))
        assertNull(TolerantJson.firstInt(null))
    }
}
