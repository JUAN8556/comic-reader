package mx.jano.comics.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import mx.jano.comics.drive.AppDataStore
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.settings.AtomicFiles
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProgressStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var now = 1_000L

    @After fun tearDown() = scope.cancel()

    private class FakeAppData : AppDataStore {
        val files = HashMap<String, ByteArray>()
        var puts = 0
        var fail: Exception? = null
        override suspend fun list() = files.keys.map { DriveFile(it, it, "application/json") }
        override suspend fun put(name: String, bytes: ByteArray, mimeType: String): DriveFile {
            fail?.let { throw it }
            puts++
            files[name] = bytes
            return DriveFile(name, name, mimeType)
        }
        override suspend fun get(name: String): ByteArray? {
            fail?.let { throw it }
            return files[name]
        }
    }

    private fun file() = File(tmp.root, "files/progress.json")

    private fun store(appData: AppDataStore? = null, f: File = file()) =
        JsonProgressStore(f, appData, scope, clock = { now }, log = { println("[progreso] $it") })

    private fun book(id: String, md5: String? = "md5-$id", size: Long = 1000) =
        Book(id = id, name = "$id.cbr", folderId = "F", path = "Serie/$id.cbr", size = size, md5 = md5)

    @Test fun estados_no_leido_leyendo_leido() = runBlocking {
        val s = store()
        val b = book("a")
        assertEquals(ReadState.UNREAD, s.stateOf(b))
        s.update(b, 0, 30)
        assertEquals(ReadState.UNREAD, s.stateOf(b))   // abrir sin pasar de la portada
        s.update(b, 9, 30)
        assertEquals(ReadState.READING, s.stateOf(b))
        assertEquals(9, s.get(b.contentKey)!!.page)
        s.update(b, 29, 30)
        assertEquals(ReadState.FINISHED, s.stateOf(b)) // última página
        s.update(b, 3, 30)
        assertEquals(ReadState.FINISHED, s.stateOf(b)) // releer no lo desmarca
        assertEquals(3, s.get(b.contentKey)!!.page)
    }

    @Test fun la_clave_es_el_contenido() = runBlocking {
        val s = store()
        val original = book("a", md5 = "abc", size = 50)
        s.update(original, 5, 20)
        // El mismo fichero movido y renombrado (otro id, mismo md5 y tamaño) conserva el progreso.
        val moved = original.copy(id = "otro-id", name = "Renombrado.cbr", path = "Otra/Renombrado.cbr")
        assertEquals(ReadState.READING, s.stateOf(moved))
        assertEquals("abc:50", moved.contentKey)
        // Sin md5: el id.
        assertEquals("x:7", book("x", md5 = null, size = 7).contentKey)
    }

    @Test fun marcar_y_desmarcar_uno_y_varios() = runBlocking {
        val s = store()
        val a = book("a")
        s.update(a, 4, 10)
        s.setFinished(a, true)
        assertEquals(ReadState.FINISHED, s.stateOf(a))
        assertEquals(9, s.get(a.contentKey)!!.page)
        s.setFinished(a, false)
        assertEquals(ReadState.UNREAD, s.stateOf(a))
        assertEquals(0, s.get(a.contentKey)!!.page)
        assertEquals(10, s.get(a.contentKey)!!.pageCount)

        val many = (1..50).map { book("s$it") }
        s.setFinished(many, true)
        assertTrue(many.all { s.stateOf(it) == ReadState.FINISHED })
        s.setFinished(many.take(10), false)
        assertEquals(40, many.count { s.stateOf(it) == ReadState.FINISHED })

        val c = book("c")
        s.setFinished(c, true, pageCount = 22)
        assertEquals(21, s.get(c.contentKey)!!.page)
    }

    @Test fun recientes_primero_sin_los_no_leidos() = runBlocking {
        val s = store()
        s.update(book("a"), 3, 10); now += 10
        s.update(book("b"), 4, 10); now += 10
        s.update(book("c"), 0, 10); now += 10   // no leído: no sale
        s.setFinished(book("d"), true, 5); now += 10
        s.update(book("a"), 5, 10)
        assertEquals(listOf("md5-a:1000", "md5-d:1000", "md5-b:1000"), s.recent(10).map { it.contentKey })
        assertEquals(2, s.recent(2).size)
        assertTrue(s.recent(0).isEmpty())
    }

    @Test fun persiste_atomico_con_bak() = runBlocking {
        val s = store()
        s.update(book("a"), 3, 10)
        s.flush()
        s.update(book("a"), 4, 10)
        s.flush()
        assertTrue(file().isFile)
        assertTrue(AtomicFiles.backupOf(file()).isFile)
        assertEquals(4, store().get("md5-a:1000")!!.page)

        file().writeText("{roto")
        assertEquals(3, store().get("md5-a:1000")!!.page) // el .bak
    }

    @Test fun las_rafagas_se_escriben_sin_perder_la_ultima() = runBlocking {
        val s = store()
        val b = book("a")
        for (p in 1..200) s.update(b, p, 300)
        s.flush()
        assertEquals(200, store().get(b.contentKey)!!.page)
    }

    @Test fun copia_en_drive_sube_la_mezcla() = runBlocking {
        val appData = FakeAppData()
        // En Drive ya hay progreso de otro dispositivo: «b» más nuevo, «a» más viejo.
        now = 5_000
        val remote = store(f = File(tmp.root, "otro/progress.json"))
        remote.update(book("b"), 7, 10)
        now = 1_000
        remote.update(book("a"), 1, 10)
        appData.files["progress.json"] = AtomicFiles.json.encodeToString(
            ProgressFile.serializer(), ProgressFile(entries = remote.all.value),
        ).toByteArray()

        now = 2_000
        val s = store(appData)
        s.update(book("a"), 6, 10)
        s.update(book("b"), 2, 10)   // 2.000 < 5.000: en Drive gana la de Drive
        assertTrue(s.backupToDrive().isSuccess)
        assertEquals(1, appData.puts)

        val uploaded = AtomicFiles.json.decodeFromString(ProgressFile.serializer(), appData.files["progress.json"]!!.decodeToString())
        assertEquals(6, uploaded.entries["md5-a:1000"]!!.page)
        assertEquals(7, uploaded.entries["md5-b:1000"]!!.page)
        // Lo local no cambia al hacer copia.
        assertEquals(2, s.get("md5-b:1000")!!.page)
    }

    @Test fun recuperar_de_drive_mezcla_por_fecha() = runBlocking {
        val appData = FakeAppData()
        now = 10_000
        val other = store(f = File(tmp.root, "otro/progress.json"))
        other.update(book("a"), 8, 10)          // más nuevo que el local
        other.setFinished(book("z"), true, 3)    // solo en Drive
        now = 1_000
        other.update(book("b"), 1, 10)          // más viejo que el local
        appData.files["progress.json"] = AtomicFiles.json.encodeToString(
            ProgressFile.serializer(), ProgressFile(entries = other.all.value),
        ).toByteArray()

        now = 5_000
        val s = store(appData)
        s.update(book("a"), 2, 10)
        s.update(book("b"), 6, 10)
        val restored = s.restoreFromDrive()
        assertEquals(2, restored.getOrThrow())
        assertEquals(8, s.get("md5-a:1000")!!.page)
        assertEquals(6, s.get("md5-b:1000")!!.page)
        assertEquals(ReadState.FINISHED, s.stateOf(book("z")))
        // Y queda en disco.
        assertEquals(8, store().get("md5-a:1000")!!.page)
        // Repetir no cambia nada.
        assertEquals(0, s.restoreFromDrive().getOrThrow())
    }

    @Test fun desmarcar_gana_a_una_copia_vieja_marcada() = runBlocking {
        val appData = FakeAppData()
        now = 1_000
        val s = store(appData)
        s.setFinished(book("a"), true, 10)
        assertTrue(s.backupToDrive().isSuccess)
        now = 2_000
        s.setFinished(book("a"), false)
        assertEquals(0, s.restoreFromDrive().getOrThrow())
        assertEquals(ReadState.UNREAD, s.stateOf(book("a")))
    }

    @Test fun sin_copia_en_drive_recupera_cero() = runBlocking {
        assertEquals(0, store(FakeAppData()).restoreFromDrive().getOrThrow())
    }

    @Test fun copia_rota_en_drive_no_toca_lo_local() = runBlocking {
        val appData = FakeAppData()
        appData.files["progress.json"] = "no es json".toByteArray()
        val s = store(appData)
        s.update(book("a"), 3, 10)
        val r = s.restoreFromDrive()
        assertTrue(r.isFailure)
        assertEquals(LibraryStrings.BACKUP_CORRUPT, r.exceptionOrNull()!!.message)
        assertEquals(3, s.get("md5-a:1000")!!.page)
        // La copia sí puede sustituirla.
        assertTrue(s.backupToDrive().isSuccess)
        assertTrue(appData.files["progress.json"]!!.decodeToString().contains("md5-a:1000"))
    }

    @Test fun errores_de_drive_son_resultados_no_excepciones() = runBlocking {
        val appData = FakeAppData().apply { fail = java.io.IOException("sin red") }
        val s = store(appData)
        assertTrue(s.backupToDrive().isFailure)
        assertTrue(s.restoreFromDrive().isFailure)
        assertTrue(store(null).backupToDrive().isFailure)
    }

    @Test fun mezcla_pura() {
        val local = mapOf("k" to ReadingProgress("k", 1, 10, 100, false))
        val remote = mapOf(
            "k" to ReadingProgress("k", 5, 10, 100, false),     // empate: gana la local
            "n" to ReadingProgress("otra", 2, 10, 50, false),   // clave corregida
        )
        val r = ProgressMerge.merge(local, remote)
        assertEquals(1, r.entries["k"]!!.page)
        assertEquals("n", r.entries["n"]!!.contentKey)
        assertEquals(1, r.changedLocally)
        assertNull(ProgressMerge.merge(local, emptyMap()).entries["n"])
        assertFalse(ProgressMerge.merge(emptyMap(), emptyMap()).entries.isNotEmpty())
    }
}
