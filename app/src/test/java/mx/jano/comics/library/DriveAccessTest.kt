package mx.jano.comics.library

import android.content.Intent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.junit4.MockWebServerRule
import mx.jano.comics.auth.Scopes
import mx.jano.comics.auth.TokenProvider
import mx.jano.comics.drive.DriveApi
import mx.jano.comics.drive.DriveAbout
import mx.jano.comics.drive.DriveFile
import mx.jano.comics.drive.DriveRestApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DriveAccessTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private val server get() = serverRule.server

    private object Tokens : TokenProvider {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
        override suspend fun invalidate(token: String) {}
        override fun completeConsent(data: Intent?): List<String> = emptyList()
        override val scopes = listOf(Scopes.DRIVE_READONLY)
    }

    private fun api() = DriveRestApi(Tokens, baseUrl = server.url("/drive/v3/"), sleeper = {}, log = { println("[sonda] $it") })

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()

    @Test fun consulta_de_varios_padres() {
        assertEquals("('a' in parents or 'b\\'c' in parents) and trashed=false", parentsQuery(listOf("a", "b'c")))
    }

    @Test fun lista_varios_padres_con_headRevisionId_y_paginando() = runTest {
        server.enqueue(json("""{"nextPageToken":"p2","files":[{"id":"x","name":"X 01.cbr","mimeType":"application/x-cbr","size":"10","md5Checksum":"m","headRevisionId":"r","parents":["a"]}]}"""))
        server.enqueue(json("""{"files":[{"id":"d","name":"Sub","mimeType":"application/vnd.google-apps.folder","parents":["b"]}]}"""))
        val items = api().listChildrenOfParents(listOf("a", "b"))
        assertEquals(listOf("x", "d"), items.map { it.id })
        assertEquals("r", items[0].headRevisionId)
        assertEquals(10L, items[0].size)
        assertTrue(items[1].isFolder)
        val r1 = server.takeRequest()
        assertEquals("('a' in parents or 'b' in parents) and trashed=false", r1.url.queryParameter("q"))
        assertEquals("nextPageToken,files(id,name,mimeType,size,md5Checksum,headRevisionId,modifiedTime,parents)", r1.url.queryParameter("fields"))
        assertEquals("1000", r1.url.queryParameter("pageSize"))
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test fun lee_un_rango_con_206() = runTest {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 100-104/1000").body("hola!").build())
        val bytes = api().readRange("F", 100, 5)
        assertArrayEquals("hola!".toByteArray(), bytes)
        val r = server.takeRequest()
        assertEquals("bytes=100-104", r.headers["Range"])
        assertEquals("media", r.url.queryParameter("alt"))
    }

    @Test fun rango_ignorado_con_200_salta_los_primeros_bytes() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        assertArrayEquals("345".toByteArray(), api().readRange("F", 3, 3))
    }

    @Test fun rango_mas_alla_del_final_da_vacio() = runTest {
        server.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */10").build())
        assertEquals(0, api().readRange("F", 50, 3).size)
    }

    @Test fun rango_con_content_range_de_otro_sitio_falla() = runTest {
        server.enqueue(MockResponse.Builder().code(206).addHeader("Content-Range", "bytes 0-4/1000").body("hola!").build())
        val e = runCatching { api().readRange("F", 100, 5) }.exceptionOrNull()
        assertTrue("$e", e is mx.jano.comics.drive.DriveHttpException)
    }

    @Test fun listador_paralelo_si_no_es_el_rest() = runTest {
        val concurrent = AtomicInteger(0)
        val max = AtomicInteger(0)
        val other = object : DriveApi {
            override suspend fun findFolder(name: String, parentId: String): DriveFile? = null
            override suspend fun listChildren(folderId: String): List<DriveFile> {
                val now = concurrent.incrementAndGet()
                max.updateAndGet { maxOf(it, now) }
                kotlinx.coroutines.delay(10)
                concurrent.decrementAndGet()
                return listOf(DriveFile("c-$folderId", "x.cbz", "application/zip"))
            }
            override suspend fun download(fileId: String, dest: File, resume: Boolean, onProgress: (Long, Long?) -> Unit) = error("no")
            override suspend fun about() = DriveAbout()
        }
        val lister = DriveAccess.listerFor(other)
        val items = lister.list((1..12).map { "p$it" })
        assertEquals(12, items.size)
        assertEquals(listOf("p1"), items.first { it.id == "c-p1" }.parents)
        assertTrue("máximo ${max.get()}", max.get() <= 4)
        assertNull(DriveAccess.rangeReaderFor(other))
    }
}
