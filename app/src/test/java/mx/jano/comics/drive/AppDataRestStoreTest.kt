package mx.jano.comics.drive

import android.content.Intent
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import mx.jano.comics.auth.Scopes
import mx.jano.comics.auth.TokenProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.random.Random

class AppDataRestStoreTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    private val server get() = serverRule.server

    private class FakeTokens(vararg tokens: String) : TokenProvider {
        private val queue = ArrayDeque(tokens.toList())
        val invalidated = mutableListOf<String>()
        override suspend fun accessToken(forceRefresh: Boolean): String = queue.first()
        override suspend fun invalidate(token: String) {
            invalidated += token
            if (queue.size > 1 && queue.first() == token) queue.removeFirst()
        }
        override fun completeConsent(data: Intent?): List<String> = error("no se usa")
        override val scopes = listOf(Scopes.DRIVE_READONLY, Scopes.DRIVE_APPDATA)
    }

    private val sleeps = mutableListOf<Long>()

    private fun store(tokens: TokenProvider = FakeTokens("t1")) = AppDataRestStore(
        DriveRestApi(tokens, baseUrl = server.url("/drive/v3/"), sleeper = { sleeps += it }, random = Random(1), log = { println("[sonda] $it") }),
        log = { println("[appdata] $it") },
    )

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(body)
        .build()

    private fun error(code: Int, reason: String) =
        json(code, """{"error":{"code":$code,"message":"m","errors":[{"domain":"global","reason":"$reason","message":"m"}]}}""")

    private fun file(id: String, name: String = "progress.json", modified: String = "2026-10-30T10:00:00.000Z") =
        """{"id":"$id","name":"$name","mimeType":"application/json","size":"12","md5Checksum":"abc","modifiedTime":"$modified"}"""

    private fun RecordedRequest.bodyText() = body?.utf8() ?: ""

    @Test fun list_pide_el_espacio_appdata_y_pagina() = runTest {
        server.enqueue(json(200, """{"nextPageToken":"p2","files":[${file("a")}]}"""))
        server.enqueue(json(200, """{"files":[${file("b", "otro.json")}]}"""))
        val files = store().list()
        assertEquals(listOf("a", "b"), files.map { it.id })
        assertEquals(12L, files[0].size)
        val r1 = server.takeRequest()
        assertEquals("GET", r1.method)
        assertEquals("/drive/v3/files", r1.url.encodedPath)
        assertEquals("appDataFolder", r1.url.queryParameter("spaces"))
        assertEquals("nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime)", r1.url.queryParameter("fields"))
        assertEquals("Bearer t1", r1.headers["Authorization"])
        assertEquals("p2", server.takeRequest().url.queryParameter("pageToken"))
    }

    @Test fun put_crea_con_multipart_la_primera_vez() = runTest {
        server.enqueue(json(200, """{"files":[]}"""))                 // búsqueda por nombre: no hay
        server.enqueue(json(200, file("NEW")))                        // creación
        val created = store().put("progress.json", """{"x":1}""".toByteArray())
        assertEquals("NEW", created.id)

        val search = server.takeRequest()
        assertEquals("/drive/v3/files", search.url.encodedPath)
        assertEquals("appDataFolder", search.url.queryParameter("spaces"))
        assertEquals("name = 'progress.json' and trashed=false", search.url.queryParameter("q"))

        val create = server.takeRequest()
        assertEquals("POST", create.method)
        assertEquals("/upload/drive/v3/files", create.url.encodedPath)
        assertEquals("multipart", create.url.queryParameter("uploadType"))
        assertTrue(create.headers["Content-Type"]!!.startsWith("multipart/related; boundary="))
        val body = create.bodyText()
        assertTrue(body, body.contains(""""name":"progress.json""""))
        assertTrue(body, body.contains(""""parents":["appDataFolder"]"""))
        assertTrue(body, body.contains("""{"x":1}"""))
        assertTrue(body, body.contains("Content-Type: application/json; charset=UTF-8"))
    }

    @Test fun put_actualiza_con_patch_si_ya_existe_y_recuerda_el_id() = runTest {
        server.enqueue(json(200, """{"files":[${file("OLD", modified = "2026-10-01T00:00:00.000Z")},${file("NEWER", modified = "2026-10-29T00:00:00.000Z")}]}"""))
        server.enqueue(json(200, file("NEWER")))
        server.enqueue(json(200, file("NEWER")))
        val s = store()
        assertEquals("NEWER", s.put("progress.json", "v1".toByteArray()).id)
        assertEquals("NEWER", s.put("progress.json", "v2".toByteArray()).id)

        server.takeRequest() // búsqueda (una sola vez)
        val p1 = server.takeRequest()
        assertEquals("PATCH", p1.method)
        assertEquals("/upload/drive/v3/files/NEWER", p1.url.encodedPath)
        assertEquals("media", p1.url.queryParameter("uploadType"))
        assertEquals("v1", p1.bodyText())
        assertTrue(p1.headers["Content-Type"]!!.startsWith("application/json"))
        val p2 = server.takeRequest()
        assertEquals("PATCH", p2.method)
        assertEquals("v2", p2.bodyText())
        assertEquals(3, server.requestCount)
    }

    @Test fun put_con_404_en_el_patch_crea_de_nuevo() = runTest {
        server.enqueue(json(200, """{"files":[${file("GONE")}]}"""))
        server.enqueue(error(404, "notFound"))
        server.enqueue(json(200, file("NEW")))
        assertEquals("NEW", store().put("progress.json", "v".toByteArray()).id)
        server.takeRequest()
        assertEquals("PATCH", server.takeRequest().method)
        assertEquals("POST", server.takeRequest().method)
    }

    @Test fun get_baja_el_contenido_o_null() = runTest {
        server.enqueue(json(200, """{"files":[${file("F")}]}"""))
        server.enqueue(MockResponse.Builder().code(200).body("contenido").build())
        server.enqueue(json(200, """{"files":[]}"""))
        val s = store()
        assertArrayEquals("contenido".toByteArray(), s.get("progress.json"))
        assertNull(s.get("otro.json"))
        server.takeRequest()
        val media = server.takeRequest()
        assertEquals("/drive/v3/files/F", media.url.encodedPath)
        assertEquals("media", media.url.queryParameter("alt"))
    }

    @Test fun un_401_renueva_el_token_y_reenvia_el_cuerpo() = runTest {
        val tokens = FakeTokens("t1", "t2")
        server.enqueue(json(200, """{"files":[${file("F")}]}"""))
        server.enqueue(error(401, "authError"))
        server.enqueue(json(200, file("F")))
        store(tokens).put("progress.json", "datos".toByteArray())
        server.takeRequest()
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("Bearer t1", first.headers["Authorization"])
        assertEquals("Bearer t2", retry.headers["Authorization"])
        assertEquals("datos", retry.bodyText())
        assertEquals(listOf("t1"), tokens.invalidated)
    }

    @Test fun cuota_agotada_espera_y_reintenta() = runTest {
        server.enqueue(error(429, "rateLimitExceeded"))
        server.enqueue(json(200, """{"files":[]}"""))
        assertTrue(store().list().isEmpty())
        assertEquals(1, sleeps.size)
        assertEquals(2, server.requestCount)
    }
}
