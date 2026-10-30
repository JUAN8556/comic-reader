package mx.jano.comics.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.text.Normalizer

class SettingsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "files/settings.json")

    @Test fun sin_fichero_valores_por_defecto() {
        val store = JsonSettingsStore(file())
        assertEquals(AppSettings(), store.app.value)
        assertEquals(ReaderSettings(), store.reader.value)
        assertEquals(20, store.app.value.cacheLimitGb)
        assertEquals(15, store.app.value.minFreeGb)
        assertFalse(file().exists())
    }

    @Test fun guarda_y_recarga() = runTest {
        val store = JsonSettingsStore(file())
        store.updateApp { it.copy(rootFolderId = "ROOT", rootFolderName = "Cómics", theme = ThemeMode.HIGH_CONTRAST) }
        store.updateReader { it.copy(fit = FitMode.WIDTH, layout = PageLayout.VERTICAL, rtlGlobal = true) }
        assertEquals("ROOT", store.app.value.rootFolderId)
        assertTrue(file().isFile)

        val again = JsonSettingsStore(file())
        assertEquals("ROOT", again.app.value.rootFolderId)
        assertEquals(ThemeMode.HIGH_CONTRAST, again.app.value.theme)
        assertEquals(FitMode.WIDTH, again.reader.value.fit)
        assertEquals(PageLayout.VERTICAL, again.reader.value.layout)
        assertTrue(again.reader.value.rtlGlobal)
    }

    @Test fun fichero_roto_usa_el_bak() = runTest {
        val store = JsonSettingsStore(file())
        store.updateApp { it.copy(rootFolderId = "A") }
        store.updateApp { it.copy(rootFolderId = "B") }
        assertTrue(AtomicFiles.backupOf(file()).isFile)
        file().writeText("{ esto no es json")
        val again = JsonSettingsStore(file())
        assertEquals("A", again.app.value.rootFolderId) // el .bak tiene la versión anterior
    }

    @Test fun fichero_y_bak_rotos_dan_valores_por_defecto() {
        file().parentFile!!.mkdirs()
        file().writeText("basura")
        AtomicFiles.backupOf(file()).writeText("")
        val logs = mutableListOf<String>()
        val store = JsonSettingsStore(file(), log = { logs += it })
        assertEquals(AppSettings(), store.app.value)
        assertTrue(logs.any { "ilegible" in it })
    }

    @Test fun enum_desconocido_y_campos_nuevos_no_rompen() {
        file().parentFile!!.mkdirs()
        file().writeText(
            """{"version":2,"app":{"rootFolderId":"R","theme":"TEMA_DEL_FUTURO","campoNuevo":1},
               "reader":{"fit":"WIDTH","layout":"OTRO"},"rtl":{"Manga":true},"otro":[1,2]}""",
        )
        val store = JsonSettingsStore(file())
        assertEquals("R", store.app.value.rootFolderId)
        assertEquals(ThemeMode.AUTO_NXTPAPER, store.app.value.theme)
        assertEquals(FitMode.WIDTH, store.reader.value.fit)
        assertEquals(PageLayout.DOUBLE_AUTO, store.reader.value.layout)
        assertTrue(store.rtlFor("Manga/Naruto/Naruto 01.cbz"))
    }

    @Test fun rtl_libro_carpeta_global() = runTest {
        val store = JsonSettingsStore(file())
        val book = "Manga/Naruto/Naruto - Tomo 54 (#505-514).cbr"
        assertFalse(store.rtlFor(book))

        store.setRtl("Manga", true)
        assertTrue(store.rtlFor(book))                    // carpeta antepasada
        assertTrue(store.rtlFor("Manga/Otro.cbz"))
        assertFalse(store.rtlFor("Americano/Marvel/X.cbr"))

        store.setRtl("Manga/Naruto", false)
        assertFalse(store.rtlFor(book))                   // la carpeta más cercana gana
        assertTrue(store.rtlFor("Manga/One Piece/1.cbz"))

        store.setRtl(book, true)
        assertTrue(store.rtlFor(book))                    // el libro gana a todo
        assertFalse(store.rtlFor("Manga/Naruto/Naruto - Tomo 1.cbr"))

        store.setRtl(book, null)
        assertFalse(store.rtlFor(book))                   // vuelve a heredar
        assertNull(store.rtlOverride(book))
        assertEquals(false, store.rtlOverride("Manga/Naruto/"))

        store.updateReader { it.copy(rtlGlobal = true) }
        assertTrue(store.rtlFor("Americano/Marvel/X.cbr")) // global
        assertFalse(store.rtlFor(book))                   // la carpeta sigue mandando

        val again = JsonSettingsStore(file())
        assertFalse(again.rtlFor(book))
        assertTrue(again.rtlFor("Manga/Otro.cbz"))
        assertTrue(again.rtlFor("Americano/X.cbr"))
        assertEquals(mapOf("Manga" to true, "Manga/Naruto" to false), again.rtlTable.value)
    }

    @Test fun rtl_normaliza_rutas_nfd_y_barras() = runTest {
        val store = JsonSettingsStore(file())
        store.setRtl(Normalizer.normalize("/Manga/Pokémon/", Normalizer.Form.NFD), true)
        assertTrue(store.rtlFor("Manga/Pokémon/Tomo 1.cbz"))
        assertTrue(store.rtlFor(Normalizer.normalize("Manga\\Pokémon\\Tomo 2.cbz", Normalizer.Form.NFD)))
        assertEquals(setOf("Manga/Pokémon"), store.rtlTable.value.keys)
    }

    @Test fun rtl_en_la_raiz_vale_para_todo() = runTest {
        val store = JsonSettingsStore(file())
        store.setRtl("", true)
        assertTrue(store.rtlFor("Cualquier/cosa.cbz"))
        assertTrue(store.rtlFor("suelto.cbz"))
    }

    @Test fun escritura_atomica_no_deja_temporales() = runTest {
        val store = JsonSettingsStore(file())
        repeat(5) { i -> store.updateApp { it.copy(cacheLimitGb = 20 + i) } }
        val leftovers = file().parentFile!!.listFiles()!!.map { it.name }.filter { it.endsWith(".tmp") }
        assertEquals(emptyList<String>(), leftovers)
        assertEquals(24, JsonSettingsStore(file()).app.value.cacheLimitGb)
    }

    @Test fun limpia_temporales_huerfanos_al_cargar() {
        file().parentFile!!.mkdirs()
        val orphan = File(file().parentFile, ".settings.json.123.tmp").apply { writeText("x") }
        JsonSettingsStore(file())
        assertFalse(orphan.exists())
    }

    @Test fun sin_cambios_no_escribe() = runTest {
        val store = JsonSettingsStore(file())
        store.updateApp { it }
        assertFalse(file().exists())
    }

    @Test fun resolver_puro() {
        val table = mapOf("A" to true, "A/B" to false)
        assertTrue(RtlResolver.resolve(table, "A/x.cbz", false))
        assertFalse(RtlResolver.resolve(table, "A/B/C/x.cbz", true))
        assertTrue(RtlResolver.resolve(table, "Z/x.cbz", true))
        assertFalse(RtlResolver.resolve(emptyMap(), "Z/x.cbz", false))
    }
}
