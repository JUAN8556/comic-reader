package mx.jano.comics.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import mx.jano.comics.cache.TransferItem
import mx.jano.comics.cache.TransferQueue
import mx.jano.comics.library.Book
import mx.jano.comics.library.JsonProgressStore
import mx.jano.comics.library.ProgressStore
import mx.jano.comics.library.ReadState
import mx.jano.comics.library.ReadingProgress
import mx.jano.comics.settings.AppSettings
import mx.jano.comics.settings.ReaderSettings
import mx.jano.comics.settings.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/** La sesión de lectura contra dobles de los contratos de progreso, transferencias y ajustes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSessionTest {

    private val book = Book(id = "b1", name = "Akira 01.cbz", folderId = "f", path = "Manga/Akira/Akira 01.cbz", size = 100, md5 = "aa")
    private val next = Book(id = "b2", name = "Akira 02.cbz", folderId = "f", path = "Manga/Akira/Akira 02.cbz", size = 100, md5 = "bb")

    /** Progreso en memoria, como lo describe el contrato. */
    private class FakeProgress : ProgressStore {
        private val map = MutableStateFlow<Map<String, ReadingProgress>>(emptyMap())
        val calls = mutableListOf<String>()
        override val all: StateFlow<Map<String, ReadingProgress>> = map
        override fun get(contentKey: String) = map.value[contentKey]
        override fun stateOf(book: Book) = get(book.contentKey)?.state ?: ReadState.UNREAD
        override suspend fun update(book: Book, page: Int, pageCount: Int) {
            calls += "update ${book.id} $page/$pageCount"
            val old = get(book.contentKey)
            map.value = map.value + (book.contentKey to ReadingProgress(book.contentKey, page, pageCount, 0, old?.finished ?: false))
        }
        override suspend fun setFinished(book: Book, finished: Boolean, pageCount: Int?) {
            calls += "finished ${book.id} $finished"
            val old = get(book.contentKey)
            map.value = map.value + (book.contentKey to ReadingProgress(book.contentKey, old?.page ?: 0, pageCount ?: old?.pageCount ?: 0, 0, finished))
        }
        override suspend fun setFinished(books: List<Book>, finished: Boolean) = books.forEach { setFinished(it, finished, null) }
        override fun recent(limit: Int) = map.value.values.take(limit)
        override suspend fun backupToDrive() = Result.success(Unit)
        override suspend fun restoreFromDrive() = Result.success(0)
        fun seed(p: ReadingProgress) { map.value = map.value + (p.contentKey to p) }
    }

    private class FakeTransfers : TransferQueue {
        val prefetched = mutableListOf<String>()
        override val active: StateFlow<List<TransferItem>> = MutableStateFlow(emptyList())
        override fun prefetch(book: Book) { prefetched += book.id }
        override fun cancel(bookId: String) {}
        override val isOnline: StateFlow<Boolean> = MutableStateFlow(true)
        override val isUnmetered: StateFlow<Boolean> = MutableStateFlow(true)
    }

    /** Ajustes con RTL por ruta: libro > carpeta más cercana > global (lo que promete el contrato). */
    private class FakeSettings(global: Boolean) : SettingsStore {
        private val readerFlow = MutableStateFlow(ReaderSettings(rtlGlobal = global))
        val overrides = mutableMapOf<String, Boolean>()
        override val app: StateFlow<AppSettings> = MutableStateFlow(AppSettings())
        override val reader: StateFlow<ReaderSettings> = readerFlow
        override suspend fun updateApp(transform: (AppSettings) -> AppSettings) {}
        override suspend fun updateReader(transform: (ReaderSettings) -> ReaderSettings) { readerFlow.value = transform(readerFlow.value) }
        override fun rtlFor(bookPath: String): Boolean {
            overrides[bookPath]?.let { return it }
            var folder = bookPath.substringBeforeLast('/', "")
            while (folder.isNotEmpty()) {
                overrides[folder]?.let { return it }
                folder = folder.substringBeforeLast('/', "")
            }
            return readerFlow.value.rtlGlobal
        }
        override suspend fun setRtl(path: String, rtl: Boolean?) {
            if (rtl == null) overrides.remove(path) else overrides[path] = rtl
        }
    }

    private fun TestScope.session(progress: FakeProgress, transfers: FakeTransfers, prefetch: Boolean = true, nextBook: Book? = next) =
        ReadingSession(book, nextBook, progress, transfers, prefetchEnabled = { prefetch }, scope = backgroundScope)

    @Test
    fun arranca_por_lo_guardado_o_por_lo_restaurado() = runTest {
        val progress = FakeProgress()
        val s = session(progress, FakeTransfers())
        assertEquals(0, s.startPage(36))
        progress.seed(ReadingProgress(book.contentKey, 12, 36, 0, false))
        assertEquals(12, s.startPage(36))
        assertEquals(20, s.startPage(36, restoredPage = 20))
        assertEquals(12, s.startPage(36, restoredPage = 99))
        progress.seed(ReadingProgress(book.contentKey, 35, 36, 0, true))
        assertEquals(0, s.startPage(36))
    }

    @Test
    fun guarda_con_debounce_marca_terminado_y_al_cerrar_guarda_ya() = runTest {
        val progress = FakeProgress()
        val s = session(progress, FakeTransfers())
        s.onPosition(ReaderPosition(4, 5, 36))
        s.onPosition(ReaderPosition(6, 7, 36))
        advanceTimeBy(501)
        runCurrent()
        assertEquals(listOf("update b1 6/36"), progress.calls)
        s.onPosition(ReaderPosition(34, 35, 36))
        s.close()
        runCurrent()
        assertEquals(listOf("update b1 6/36", "update b1 34/36", "finished b1 true"), progress.calls)
        assertEquals(ReadState.FINISHED, progress.stateOf(book))
    }

    @Test
    fun prefetch_del_siguiente_una_sola_vez_al_pasar_del_80() = runTest {
        val transfers = FakeTransfers()
        val s = session(FakeProgress(), transfers)
        s.onPosition(ReaderPosition(20, 21, 36))
        assertEquals(emptyList<String>(), transfers.prefetched)
        s.onPosition(ReaderPosition(29, 30, 36))
        s.onPosition(ReaderPosition(31, 32, 36))
        assertEquals(listOf("b2"), transfers.prefetched)
    }

    @Test
    fun sin_prefetch_activado_o_sin_siguiente_no_se_pide() = runTest {
        val t1 = FakeTransfers()
        session(FakeProgress(), t1, prefetch = false).onPosition(ReaderPosition(35, 35, 36))
        assertTrue(t1.prefetched.isEmpty())
        val t2 = FakeTransfers()
        session(FakeProgress(), t2, nextBook = null).onPosition(ReaderPosition(35, 35, 36))
        assertTrue(t2.prefetched.isEmpty())
    }

    @Test
    fun manga_efectivo_y_heredado() = runTest {
        val settings = FakeSettings(global = false)
        assertEquals(MangaState(rtl = false, inherited = false), settings.mangaStateFor(book.path))
        settings.setRtl("Manga", true)
        assertEquals(MangaState(rtl = true, inherited = true), settings.mangaStateFor(book.path))
        assertEquals(MangaChoice.INHERIT, settings.mangaStateFor(book.path).let { MangaResolution.choiceFor(it.rtl, it.inherited) })
        settings.setRtl(book.path, false)
        val st = settings.mangaStateFor(book.path)
        assertEquals(MangaState(rtl = false, inherited = true), st)
        assertEquals(MangaChoice.NO, MangaResolution.choiceFor(st.rtl, st.inherited))
        // Volver a heredar quita el valor propio del libro.
        settings.setRtl(book.path, MangaResolution.override(MangaChoice.INHERIT))
        assertTrue(settings.mangaStateFor(book.path).rtl)
        assertFalse(book.path in settings.overrides)
    }

    /** Almacén de verdad + política: un número «Leído» que se relee hasta la mitad se retoma ahí al reabrirlo. */
    @Test
    fun releer_un_terminado_retoma_la_pagina_con_el_almacen_de_verdad() = runTest {
        val dir = createTempDirectory("progreso").toFile()
        val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = JsonProgressStore(File(dir, "progress.json"), appData = null, scope = ioScope, clock = { 1L })
            fun open() = ReadingSession(book, next, store, FakeTransfers(), prefetchEnabled = { false }, scope = backgroundScope)

            // Primera lectura hasta el final (pliego doble 159-160 de 160).
            val first = open()
            assertEquals(0, first.startPage(160))
            first.onPosition(ReaderPosition(158, 159, 160))
            first.close()
            runCurrent()
            assertEquals(ReadState.FINISHED, store.stateOf(book))

            // Se vuelve a abrir por la portada y se relee hasta la página 51.
            val second = open()
            assertEquals(0, second.startPage(160))
            second.onPosition(ReaderPosition(50, 51, 160))
            second.close()
            runCurrent()
            assertEquals(ReadState.FINISHED, store.stateOf(book))

            // La relectura se retoma donde quedó.
            val third = open()
            assertEquals(50, third.startPage(160))
            // Llegar otra vez al final («Fin del número») y salir: la siguiente apertura vuelve a la portada.
            third.onPosition(ReaderPosition(158, 159, 160, atEnd = true))
            third.close()
            runCurrent()
            assertEquals(0, open().startPage(160))
        } finally {
            ioScope.cancel()
            dir.deleteRecursively()
        }
    }
}
