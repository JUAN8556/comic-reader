package mx.jano.comics.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class LibraryRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    /** Árbol de Drive falso: id → hijos. Cuenta peticiones y padres por petición. */
    private class FakeDrive {
        val children = HashMap<String, MutableList<ScanItem>>()
        val calls = AtomicInteger(0)
        val batchSizes = Collections.synchronizedList(mutableListOf<Int>())
        val concurrent = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        var gate: CompletableDeferred<Unit>? = null
        var failWith: Exception? = null

        fun folder(parent: String, id: String, name: String) {
            children.getOrPut(parent) { mutableListOf() } += ScanItem(id, name, ScanItem.FOLDER_MIME, parents = listOf(parent))
        }

        fun file(parent: String, id: String, name: String, size: Long = 100, md5: String? = "md5$id", mime: String = "application/x-cbr") {
            children.getOrPut(parent) { mutableListOf() } += ScanItem(id, name, mime, size, md5, "rev$id", "2026-10-30T10:00:00Z", listOf(parent))
        }

        val lister = ChildLister { parents ->
            calls.incrementAndGet()
            batchSizes += parents.size
            val now = concurrent.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            try {
                gate?.await()
                delay(5)
                failWith?.let { throw it }
                parents.flatMap { children[it].orEmpty() }
            } finally {
                concurrent.decrementAndGet()
            }
        }
    }

    private fun sampleDrive() = FakeDrive().apply {
        folder("ROOT", "am", "Americano")
        folder("ROOT", "mg", "Manga")
        folder("ROOT", "idx", ".indice")
        folder("ROOT", "hid", ".oculta")
        file("idx", "i1", "indice.jsonl", mime = "application/json")
        folder("hid", "hid2", "Dentro")
        file("hid2", "hb", "no se ve.cbr")
        folder("am", "mv", "Marvel")
        folder("mv", "m2099", "Marvel 2099")
        file("m2099", "b10", "Marvel 2099 10.cbr")
        file("m2099", "b2", "Marvel 2099 02.cbr")
        file("m2099", "b1", "Marvel 2099 01.cbr")
        file("m2099", "jpg", "folder.jpg", mime = "image/jpeg")
        file("m2099", "dot", "._Marvel 2099 01.cbr")
        file("m2099", "sc", "Atajo.cbr", mime = ScanItem.SHORTCUT_MIME)
        folder("mv", "slash", "AC/DC")
        file("slash", "acdc", "AC/DC 1.cbz")
        folder("mg", "nar", "Naruto")
        file("nar", "n54", "Naruto - Tomo 54 (#505-514).cbr", size = 5000)
        file("nar", "n6", "Naruto - Tomo 6.CBR", size = 6000)
        file("nar", "pdf", "Naruto Extra.pdf", mime = "application/pdf")
        file("nar", "txt", "leeme.txt", mime = "text/plain")
        file("mg", "suelto", "Suelto.7z")
    }

    private fun repo(drive: FakeDrive, file: File = File(tmp.root, "index.json"), rootId: String = "ROOT") = DriveLibraryRepository(
        indexFile = file,
        lister = drive.lister,
        resolveRoot = { RootRef(rootId, "Cómics") },
        configuredRootId = { rootId },
        scope = scope,
        log = { println("[biblioteca] $it") },
    )

    private fun DriveLibraryRepository.awaitDisk() = runBlocking { withTimeout(5_000) { diskLoaded.first { it } } }

    @Test fun escanea_por_niveles_cuenta_y_filtra() = runBlocking {
        val drive = sampleDrive()
        val repo = repo(drive)
        val index = repo.rescan()

        assertEquals("ROOT", index.rootId)
        assertEquals(
            listOf("", "Americano", "Americano/Marvel", "Americano/Marvel/AC／DC", "Americano/Marvel/Marvel 2099", "Manga", "Manga/Naruto"),
            index.folders.map { it.path },
        )
        assertEquals(
            setOf("b1", "b2", "b10", "acdc", "n54", "n6", "pdf", "suelto"),
            index.books.map { it.id }.toSet(),
        )
        val root = repo.folder("ROOT")!!
        assertEquals(null, root.parentId)
        assertEquals(2, root.folderCount)              // .indice y .oculta no cuentan
        assertEquals(0, root.bookCount)
        assertEquals(8, root.totalBooks)
        assertEquals(1, repo.folder("mg")!!.bookCount) // Suelto.7z
        assertEquals(4, repo.folder("mg")!!.totalBooks)
        assertEquals(3, repo.folder("m2099")!!.bookCount)
        assertEquals(4, repo.folder("am")!!.totalBooks)
        assertEquals(2, repo.folder("mv")!!.folderCount)

        val b = repo.book("n54")!!
        assertEquals("Manga/Naruto/Naruto - Tomo 54 (#505-514).cbr", b.path)
        assertEquals("Manga/Naruto", b.folderPath)
        assertEquals("nar", b.folderId)
        assertEquals("md5n54:5000", b.contentKey)
        assertEquals("revn54", b.headRevisionId)
        assertEquals("Americano/Marvel/AC／DC/AC／DC 1.cbz", repo.book("acdc")!!.path)
        assertNull(repo.book("hb"))
        assertNull(repo.folder("idx"))

        // Por niveles: raíz (1 petición), nivel 1 (1), nivel 2 (1), nivel 3 (1).
        assertEquals(4, drive.calls.get())
        val state = repo.scanState.value
        assertTrue("$state", state is ScanState.Done)
        assertEquals(7, (state as ScanState.Done).folders)
        assertEquals(8, state.books)
    }

    @Test fun hijos_en_orden_natural_y_vecinos() = runBlocking {
        val repo = repo(sampleDrive())
        repo.rescan()
        val (folders, books) = repo.children("m2099")
        assertTrue(folders.isEmpty())
        assertEquals(listOf("Marvel 2099 01.cbr", "Marvel 2099 02.cbr", "Marvel 2099 10.cbr"), books.map { it.name })
        assertEquals(listOf("AC/DC", "Marvel 2099"), repo.children("mv").first.map { it.name })
        assertEquals(listOf("Americano", "Manga"), repo.children("ROOT").first.map { it.name })

        val b1 = repo.book("b1")!!
        assertEquals("b2", repo.nextAfter(b1)?.id)
        assertEquals("b10", repo.nextAfter(repo.book("b2")!!)?.id)
        assertNull(repo.nextAfter(repo.book("b10")!!))
        assertNull(repo.previousBefore(b1))
        assertEquals("b2", repo.previousBefore(repo.book("b10")!!)?.id)
        assertEquals(listOf("Naruto - Tomo 6.CBR", "Naruto - Tomo 54 (#505-514).cbr", "Naruto Extra.pdf"), repo.children("nar").second.map { it.name })

        // Un libro que ya no está en el índice encuentra su sitio por nombre.
        val ghost = b1.copy(id = "fantasma", name = "Marvel 2099 05.cbr")
        assertEquals("b10", repo.nextAfter(ghost)?.id)
        assertEquals("b2", repo.previousBefore(ghost)?.id)
    }

    @Test fun libros_bajo_una_carpeta_recursivo_en_orden_de_ruta() = runBlocking {
        val repo = repo(sampleDrive())
        repo.rescan()
        assertEquals(
            listOf("acdc", "b1", "b2", "b10"),
            repo.booksUnder("am").map { it.id },
        )
        assertEquals(listOf("n6", "n54", "pdf", "suelto"), repo.booksUnder("mg").map { it.id })
        assertEquals(8, repo.booksUnder("ROOT").size)
        assertTrue(repo.booksUnder("no-existe").isEmpty())
    }

    @Test fun lotes_de_40_padres_y_4_a_la_vez() = runBlocking {
        val drive = FakeDrive()
        repeat(170) { i -> drive.folder("ROOT", "f$i", "Serie $i"); drive.file("f$i", "b$i", "Serie $i 01.cbz") }
        val repo = repo(drive)
        val index = repo.rescan()
        assertEquals(171, index.folders.size)
        assertEquals(170, index.books.size)
        // raíz: 1 petición de 1 padre; nivel 1: 170 padres en lotes de 40 → 40, 40, 40, 40, 10.
        assertEquals(listOf(1), drive.batchSizes.take(1))
        assertEquals(listOf(10, 40, 40, 40, 40), drive.batchSizes.drop(1).sorted())
        assertTrue("máximo ${drive.maxConcurrent.get()}", drive.maxConcurrent.get() <= 4)
    }

    @Test fun persiste_y_carga_al_arrancar() = runBlocking {
        val file = File(tmp.root, "files/index.json")
        val repo = repo(sampleDrive(), file)
        repo.rescan()
        assertTrue(file.isFile)

        val drive2 = FakeDrive()
        val again = repo(drive2, file)
        again.awaitDisk()
        assertNotNull(again.index.value)
        assertEquals(8, again.index.value!!.books.size)
        assertEquals("b2", again.nextAfter(again.book("b1")!!)?.id)
        assertEquals(0, drive2.calls.get())
        assertEquals("Manga/Naruto/Naruto - Tomo 6.CBR", again.bookByPath("Manga/Naruto/Naruto - Tomo 6.CBR")?.path)
        assertEquals("nar", again.folderByPath("/Manga/Naruto/")?.id)
    }

    @Test fun index_de_otra_raiz_se_ignora() = runBlocking {
        val file = File(tmp.root, "index.json")
        repo(sampleDrive(), file).rescan()
        val other = repo(FakeDrive(), file, rootId = "OTRA")
        other.awaitDisk()
        assertNull(other.index.value)
    }

    @Test fun indice_roto_usa_el_bak() = runBlocking {
        val file = File(tmp.root, "index.json")
        val repo = repo(sampleDrive(), file)
        repo.rescan()
        repo.rescan()
        file.writeText("{roto")
        val again = repo(FakeDrive(), file)
        again.awaitDisk()
        assertEquals(8, again.index.value?.books?.size)
    }

    @Test fun fallo_conserva_el_indice_y_avisa() = runBlocking {
        val drive = sampleDrive()
        val repo = repo(drive)
        repo.rescan()
        drive.failWith = java.net.UnknownHostException("www.googleapis.com")
        try {
            repo.rescan()
            fail("tenía que fallar")
        } catch (e: java.net.UnknownHostException) {
            // esperado
        }
        val state = repo.scanState.value
        assertTrue("$state", state is ScanState.Error)
        assertEquals(LibraryStrings.OFFLINE, (state as ScanState.Error).message)
        assertEquals(8, repo.index.value!!.books.size)
    }

    @Test fun dos_rescan_a_la_vez_comparten_el_escaneo() = runBlocking {
        val drive = sampleDrive()
        drive.gate = CompletableDeferred()
        val repo = repo(drive)
        val a = async { repo.rescan() }
        val b = async { repo.rescan() }
        withTimeout(5_000) { repo.scanState.first { it is ScanState.Scanning } }
        drive.gate!!.complete(Unit)
        val ia = a.await()
        val ib = b.await()
        assertSame(ia, ib)
        assertEquals(4, drive.calls.get())
    }

    @Test fun estado_en_vivo_durante_el_escaneo() = runBlocking {
        val drive = sampleDrive()
        val seen = Collections.synchronizedList(mutableListOf<ScanState>())
        val repo = repo(drive)
        val collector = scope.async { repo.scanState.collect { seen += it } }
        repo.rescan()
        delay(50)
        collector.cancel()
        assertTrue(seen.toString(), seen.any { it is ScanState.Scanning && it.books > 0 })
        assertTrue(seen.toString(), seen.last() is ScanState.Done)
    }

    @Test fun ciclos_y_carpetas_repetidas_no_cuelgan() = runBlocking {
        val drive = FakeDrive()
        drive.folder("ROOT", "a", "A")
        drive.folder("a", "b", "B")
        drive.children.getOrPut("b") { mutableListOf() } += ScanItem("a", "A otra vez", ScanItem.FOLDER_MIME, parents = listOf("b"))
        drive.file("b", "x", "x.cbz")
        val index = repo(drive).rescan()
        assertEquals(listOf("", "A", "A/B"), index.folders.map { it.path })
        assertEquals(1, index.books.size)
    }

    @Test fun es_libro_por_extension() {
        for (ok in listOf("a.cbz", "a.CBR", "a.cb7", "a.cbt", "a.pdf", "a.zip", "a.rar", "a.7z")) {
            assertTrue(ok, DriveLibraryRepository.isBookName(ok))
        }
        for (no in listOf("a.jpg", "a.txt", "cbz", ".a.cbz", "a.", "a.epub", "ComicInfo.xml")) {
            assertFalse(no, DriveLibraryRepository.isBookName(no))
        }
    }

    @Test fun sin_indice_las_consultas_dan_vacio() {
        val repo = repo(FakeDrive())
        repo.awaitDisk()
        assertNull(repo.index.value)
        assertEquals(emptyList<Folder>() to emptyList<Book>(), repo.children("ROOT"))
        assertTrue(repo.booksUnder("ROOT").isEmpty())
        assertEquals(ScanState.Idle, repo.scanState.value)
    }
}
