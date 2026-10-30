package mx.jano.comics.drive

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import mockwebserver3.junit4.MockWebServerRule
import mx.jano.comics.auth.AccountAware
import mx.jano.comics.auth.Scopes
import mx.jano.comics.auth.TokenProvider
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class DriveRestApiTest {

    @get:Rule
    val serverRule = MockWebServerRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val server get() = serverRule.server

    /** Tokens «t1», «t2»…: invalidate pasa al siguiente. Recuerda la cuenta como [AccountAware]. */
    private class FakeTokens(vararg tokens: String) : TokenProvider, AccountAware {
        private val queue = ArrayDeque(tokens.toList())
        val invalidated = mutableListOf<String>()
        override var accountName: String? = null

        override suspend fun accessToken(forceRefresh: Boolean): String = queue.first()

        override suspend fun invalidate(token: String) {
            invalidated += token
            if (queue.size > 1 && queue.first() == token) queue.removeFirst()
        }

        override fun completeConsent(data: Intent?): List<String> = error("no se usa")
        override val scopes = listOf(Scopes.DRIVE_READONLY, Scopes.DRIVE_APPDATA)
        override fun rememberAccount(name: String) {
            accountName = name
        }
    }

    private val sleeps = mutableListOf<Long>()

    private fun api(tokens: TokenProvider) = DriveRestApi(
        tokens = tokens,
        baseUrl = server.url("/drive/v3/"),
        sleeper = { sleeps += it },
        random = Random(42),
        log = { println("[sonda] $it") },
    )

    private fun json(code: Int, body: String) = MockResponse.Builder()
        .code(code)
        .addHeader("Content-Type", "application/json; charset=UTF-8")
        .body(body)
        .build()

    private fun error(code: Int, reason: String, message: String = "mensaje") =
        json(code, """{"error":{"code":$code,"message":"$message","errors":[{"domain":"global","reason":"$reason","message":"$message"}]}}""")

    private fun RecordedRequest.auth() = headers["Authorization"]

    /** Respuesta a `files.get?fields=…` (la versión que [DriveRestApi.download] pide antes de bajar). */
    private fun meta(size: Long = 10, md5: String = "m1", rev: String = "r1") =
        json(200, """{"id":"FILE1","size":"$size","md5Checksum":"$md5","headRevisionId":"$rev","modifiedTime":"2026-10-30T10:00:00.000Z"}""")

    /** Huella del parcial [dest], como la deja una descarga cortada de esa versión. */
    private fun stamp(dest: File, size: Long = 10, md5: String = "m1", rev: String = "r1") =
        DriveRestApi.writePartialVersion(DriveRestApi.partialVersionFile(dest), PartialVersion("FILE1", rev, md5, size))

    /** Comprueba que la petición es la de la versión (sin alt=media) y la consume. */
    private fun takeMetaRequest() {
        val r = server.takeRequest()
        assertEquals("/drive/v3/files/FILE1", r.url.encodedPath)
        assertNull(r.url.queryParameter("alt"))
        assertEquals(DriveRestApi.VERSION_FIELDS, r.url.queryParameter("fields"))
    }

    @Test
    fun listChildren_pagina_hasta_agotar_y_parsea_campos() = runTest {
        server.enqueue(
            json(
                200,
                """{"nextPageToken":"p2","files":[
                  {"id":"f1","name":"Tomo 1.cbr","mimeType":"application/x-rar","size":"123456789012",
                   "md5Checksum":"abc","modifiedTime":"2026-10-01T10:00:00.000Z","parents":["F"],"extra":1}]}""",
            ),
        )
        server.enqueue(json(200, """{"files":[{"id":"d1","name":"Serie","mimeType":"application/vnd.google-apps.folder"}]}"""))

        val files = api(FakeTokens("t1")).listChildren("F")

        assertEquals(listOf("f1", "d1"), files.map { it.id })
        assertEquals(123456789012L, files[0].size)
        assertEquals("abc", files[0].md5Checksum)
        assertEquals(listOf("F"), files[0].parents)
        assertFalse(files[0].isFolder)
        assertTrue(files[1].isFolder)
        assertNull(files[1].size)

        val r1 = server.takeRequest()
        assertEquals("/drive/v3/files", r1.url.encodedPath)
        assertEquals("'F' in parents and trashed=false", r1.url.queryParameter("q"))
        assertEquals("nextPageToken,files(id,name,mimeType,size,md5Checksum,modifiedTime,parents)", r1.url.queryParameter("fields"))
        assertEquals("1000", r1.url.queryParameter("pageSize"))
        assertEquals("folder,name_natural", r1.url.queryParameter("orderBy"))
        assertNull(r1.url.queryParameter("pageToken"))
        assertEquals("Bearer t1", r1.auth())

        val r2 = server.takeRequest()
        assertEquals("p2", r2.url.queryParameter("pageToken"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun error_401_invalida_el_token_y_reintenta_una_vez() = runTest {
        server.enqueue(error(401, "authError", "Invalid Credentials"))
        server.enqueue(json(200, """{"user":{"emailAddress":"a@b.c"}}"""))
        val tokens = FakeTokens("t1", "t2")

        val about = api(tokens).about()

        assertEquals("a@b.c", about.userEmail)
        assertEquals(listOf("t1"), tokens.invalidated)
        assertEquals("Bearer t1", server.takeRequest().auth())
        assertEquals("Bearer t2", server.takeRequest().auth())
    }

    @Test
    fun error_401_dos_veces_lanza_sin_tercer_intento() = runTest {
        server.enqueue(error(401, "authError"))
        server.enqueue(error(401, "authError"))
        val tokens = FakeTokens("t1", "t2", "t3")

        try {
            api(tokens).about()
            fail("debía lanzar")
        } catch (e: DriveHttpException) {
            assertEquals(401, e.code)
            assertEquals("authError", e.reason)
        }
        assertEquals(2, server.requestCount)
        assertEquals(listOf("t1"), tokens.invalidated)
    }

    @Test
    fun download_reanuda_con_Range_y_anade_al_final() = runTest {
        val dest = tmp.newFile("tomo.cbz").apply { writeText("01234") }
        stamp(dest)
        server.enqueue(meta())
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 5-9/10")
                .body("56789")
                .build(),
        )
        val progress = mutableListOf<Pair<Long, Long?>>()

        api(FakeTokens("t1")).download("FILE1", dest, resume = true) { b, t -> progress += b to t }

        assertEquals("0123456789", dest.readText())
        takeMetaRequest()
        val req = server.takeRequest()
        assertEquals("bytes=5-", req.headers["Range"])
        assertEquals("/drive/v3/files/FILE1", req.url.encodedPath)
        assertEquals("media", req.url.queryParameter("alt"))
        assertEquals("identity", req.headers["Accept-Encoding"])
        assertEquals(5L to 10L, progress.first())
        assertEquals(10L to 10L, progress.last())
    }

    @Test
    fun download_200_ante_Range_reescribe_desde_cero() = runTest {
        val dest = tmp.newFile("tomo.cbr").apply { writeText("XXXXX") }
        stamp(dest)
        server.enqueue(meta())
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        var first = -1L

        api(FakeTokens("t1")).download("FILE1", dest) { b, _ -> if (first < 0) first = b }

        takeMetaRequest()
        assertEquals("bytes=5-", server.takeRequest().headers["Range"])
        assertEquals("el primer progreso dice desde qué byte sirvió el servidor", 0L, first)
        assertEquals("0123456789", dest.readText())
    }

    @Test
    fun download_sin_resume_no_manda_Range_y_trunca() = runTest {
        val dest = tmp.newFile("tomo.cbr").apply { writeText("XXXXXXXXXXXXXXXXXXXX") }
        server.enqueue(meta(size = 3))
        server.enqueue(MockResponse.Builder().code(200).body("abc").build())

        api(FakeTokens("t1")).download("FILE1", dest, resume = false)

        takeMetaRequest()
        assertNull(server.takeRequest().headers["Range"])
        assertEquals("abc", dest.readText())
    }

    @Test
    fun download_416_con_mismo_tamano_es_completo() = runTest {
        val dest = tmp.newFile("tomo.cbz").apply { writeText("0123456789") }
        stamp(dest)
        server.enqueue(meta())
        server.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */10").build())
        var last: Pair<Long, Long?>? = null

        api(FakeTokens("t1")).download("FILE1", dest) { b, t -> last = b to t }

        assertEquals("0123456789", dest.readText())
        assertEquals(10L to 10L, last)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun download_416_con_tamano_distinto_empieza_de_cero() = runTest {
        // Metadatos y huella dicen 12 bytes, pero el servidor contesta 416 con 10: gana lo que dice la descarga.
        val dest = tmp.newFile("tomo.cbz").apply { writeText("0123456789AB") }
        stamp(dest, size = 12)
        server.enqueue(meta(size = 12))
        server.enqueue(MockResponse.Builder().code(416).addHeader("Content-Range", "bytes */10").build())
        server.enqueue(MockResponse.Builder().code(200).body("nuevo").build())

        api(FakeTokens("t1")).download("FILE1", dest)

        takeMetaRequest()
        assertEquals("bytes=12-", server.takeRequest().headers["Range"])
        assertNull(server.takeRequest().headers["Range"])
        assertEquals("nuevo", dest.readText())
    }

    @Test
    fun download_abusivo_reintenta_una_vez_con_acknowledgeAbuse() = runTest {
        val dest = File(tmp.root, "sub/dir/tomo.cbz")
        server.enqueue(meta(size = 2))
        server.enqueue(error(403, "cannotDownloadAbusiveFile"))
        server.enqueue(MockResponse.Builder().code(200).body("ok").build())

        api(FakeTokens("t1")).download("FILE1", dest)

        takeMetaRequest()
        assertNull(server.takeRequest().url.queryParameter("acknowledgeAbuse"))
        val second = server.takeRequest()
        assertEquals("true", second.url.queryParameter("acknowledgeAbuse"))
        assertEquals("media", second.url.queryParameter("alt"))
        assertEquals("ok", dest.readText())
    }

    @Test
    fun error_429_y_403_rateLimit_hacen_backoff_y_luego_OK() = runTest {
        server.enqueue(MockResponse.Builder().code(429).build())
        server.enqueue(error(403, "userRateLimitExceeded"))
        server.enqueue(error(403, "rateLimitExceeded"))
        server.enqueue(json(200, """{"user":{"emailAddress":"a@b.c"}}"""))

        api(FakeTokens("t1")).about()

        assertEquals(4, server.requestCount)
        assertEquals(3, sleeps.size)
        assertTrue("1.º en [1, 2) s: $sleeps", sleeps[0] in 1_000L until 2_000L)
        assertTrue("2.º en [2, 3) s: $sleeps", sleeps[1] in 2_000L until 3_000L)
        assertTrue("3.º en [4, 5) s: $sleeps", sleeps[2] in 4_000L until 5_000L)
    }

    @Test
    fun backoff_respeta_Retry_After_con_tope_de_64_s() = runTest {
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "30").build())
        server.enqueue(MockResponse.Builder().code(429).addHeader("Retry-After", "600").build())
        server.enqueue(json(200, "{}"))

        api(FakeTokens("t1")).about()

        assertEquals(listOf(30_000L, 64_000L), sleeps)
    }

    @Test
    fun rateLimit_se_rinde_tras_5_intentos() = runTest {
        repeat(6) { server.enqueue(error(403, "rateLimitExceeded")) }

        try {
            api(FakeTokens("t1")).about()
            fail("debía lanzar")
        } catch (e: DriveHttpException) {
            assertEquals(403, e.code)
            assertEquals("rateLimitExceeded", e.reason)
        }
        assertEquals(5, server.requestCount)
        assertEquals(4, sleeps.size)
    }

    @Test
    fun otros_errores_lanzan_DriveHttpException_con_reason() = runTest {
        server.enqueue(error(404, "notFound", "File not found: X"))

        try {
            api(FakeTokens("t1")).download("X", tmp.newFile())
            fail("debía lanzar")
        } catch (e: DriveHttpException) {
            assertEquals(404, e.code)
            assertEquals("notFound", e.reason)
            assertTrue(e.message!!.contains("File not found: X"))
        }
        assertEquals(1, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun error_con_cuerpo_no_JSON_lanza_sin_reason() = runTest {
        server.enqueue(MockResponse.Builder().code(500).body("<html>oops</html>").build())

        try {
            api(FakeTokens("t1")).about()
            fail("debía lanzar")
        } catch (e: DriveHttpException) {
            assertEquals(500, e.code)
            assertNull(e.reason)
        }
    }

    @Test
    fun findFolder_escapa_comillas_y_barras() = runTest {
        server.enqueue(json(200, """{"files":[{"id":"C1","name":"x","mimeType":"application/vnd.google-apps.folder"}]}"""))

        val folder = api(FakeTokens("t1")).findFolder("Rock 'n' Roll \\ Cómics", "root")

        assertEquals("C1", folder?.id)
        val q = server.takeRequest().url.queryParameter("q")
        assertEquals(
            "name = 'Rock \\'n\\' Roll \\\\ Cómics' and mimeType = 'application/vnd.google-apps.folder' " +
                "and 'root' in parents and trashed=false",
            q,
        )
    }

    @Test
    fun findFolder_sin_resultados_devuelve_null() = runTest {
        server.enqueue(json(200, """{"files":[]}"""))
        assertNull(api(FakeTokens("t1")).findFolder("Cómics"))
    }

    @Test
    fun about_parsea_cuota_en_cadenas_y_recuerda_la_cuenta() = runTest {
        server.enqueue(
            json(200, """{"user":{"emailAddress":"jano@example.com","displayName":"J"},"storageQuota":{"limit":"16106127360","usage":"123","usageInDrive":"1"}}"""),
        )
        val tokens = FakeTokens("t1")

        val about = api(tokens).about()

        assertEquals(DriveAbout("jano@example.com", 16106127360L, 123L), about)
        assertEquals("user(emailAddress),storageQuota(limit,usage)", server.takeRequest().url.queryParameter("fields"))
        assertEquals("jano@example.com", tokens.accountName)
    }

    @Test
    fun about_sin_limite_de_cuota() = runTest {
        server.enqueue(json(200, """{"user":{"emailAddress":"x@y.z"},"storageQuota":{"usage":"5"}}"""))
        assertEquals(DriveAbout("x@y.z", null, 5L), api(FakeTokens("t1")).about())
    }

    @Test
    fun descarga_cancelada_deja_un_prefijo_valido_que_se_reanuda() = runBlocking {
        val content = Random(7).nextBytes(256 * 1024)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.url.queryParameter("alt") == null) return meta(size = content.size.toLong())
                val range = request.headers["Range"]
                return if (range == null) {
                    // Primer intento: lento, para cancelarlo a medias.
                    MockResponse.Builder().code(200)
                        .body(Buffer().write(content))
                        .throttleBody(16 * 1024, 50, TimeUnit.MILLISECONDS)
                        .build()
                } else {
                    val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
                    MockResponse.Builder().code(206)
                        .addHeader("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
                        .body(Buffer().write(content, start, content.size - start))
                        .build()
                }
            }
        }
        val dest = tmp.newFile("cancelada.cbz")
        val api = api(FakeTokens("t1"))

        val halfway = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) {
            api.download("FILE1", dest) { bytes, _ -> if (bytes >= 64 * 1024) halfway.complete(Unit) }
        }
        withTimeout(10_000) { halfway.await() }
        withTimeout(5_000) { job.cancelAndJoin() }

        val partial = dest.readBytes()
        assertTrue("parcial: ${partial.size}", partial.size in (64 * 1024) until content.size)
        assertArrayEquals(content.copyOf(partial.size), partial)

        api.download("FILE1", dest)

        takeMetaRequest()
        assertNull(server.takeRequest().headers["Range"]) // la primera, la cortada
        takeMetaRequest()
        assertEquals("bytes=${partial.size}-", server.takeRequest().headers["Range"])
        assertArrayEquals(content, dest.readBytes())
    }

    @Test
    fun download_deja_la_huella_de_la_version_junto_al_destino() = runTest {
        val dest = File(tmp.root, "nuevo.cbr")
        server.enqueue(meta(size = 3, md5 = "abc123", rev = "rev9"))
        server.enqueue(MockResponse.Builder().code(200).body("abc").build())

        api(FakeTokens("t1")).download("FILE1", dest)

        val saved = DriveRestApi.readPartialVersion(DriveRestApi.partialVersionFile(dest))
        assertEquals(PartialVersion("FILE1", "rev9", "abc123", 3, "2026-10-30T10:00:00.000Z"), saved)
    }

    @Test
    fun download_parcial_de_otra_version_se_descarta_y_no_pide_Range() = runTest {
        // AD-1: se cortó «Tomo 1» a 5 bytes; luego rclone subió otra versión (mismo id, otro md5 y otra revisión).
        val dest = tmp.newFile("tomo.cbr").apply { writeText("VIEJO") }
        stamp(dest, size = 10, md5 = "viejo", rev = "r1")
        server.enqueue(meta(size = 10, md5 = "nuevo", rev = "r2"))
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())
        var first = -1L

        api(FakeTokens("t1")).download("FILE1", dest) { b, _ -> if (first < 0) first = b }

        takeMetaRequest()
        assertNull("no se reanuda sobre bytes de otra versión", server.takeRequest().headers["Range"])
        assertEquals("0123456789", dest.readText())
        assertEquals(0L, first)
        assertEquals("nuevo", DriveRestApi.readPartialVersion(DriveRestApi.partialVersionFile(dest))?.md5Checksum)
    }

    @Test
    fun download_parcial_sin_huella_empieza_de_cero() = runTest {
        val dest = tmp.newFile("tomo.cbr").apply { writeText("01234") }
        server.enqueue(meta())
        server.enqueue(MockResponse.Builder().code(200).body("0123456789").build())

        api(FakeTokens("t1")).download("FILE1", dest)

        takeMetaRequest()
        assertNull(server.takeRequest().headers["Range"])
        assertEquals("0123456789", dest.readText())
    }

    @Test
    fun staleReason_compara_revision_md5_y_tamano_pero_no_modifiedTime() {
        val v = PartialVersion("F", "r1", "m1", 10, "2026-10-01T00:00:00Z")
        assertNull(DriveRestApi.staleReason(v, v.copy(modifiedTime = "2026-10-30T00:00:00Z"), 5))
        assertTrue(DriveRestApi.staleReason(null, v, 5)!!.contains("huella"))
        assertTrue(DriveRestApi.staleReason(v.copy(fileId = "G"), v, 5)!!.contains("otro fichero"))
        assertTrue(DriveRestApi.staleReason(v, v.copy(headRevisionId = "r2"), 5)!!.contains("revisión"))
        assertTrue(DriveRestApi.staleReason(v, v.copy(md5Checksum = "m2"), 5)!!.contains("md5"))
        assertTrue(DriveRestApi.staleReason(v, v.copy(size = 11), 5)!!.contains("tamaño"))
        assertTrue(DriveRestApi.staleReason(v, v, 11)!!.contains("más bytes"))
        val blind = PartialVersion("F", null, null, 10)
        assertTrue(DriveRestApi.staleReason(blind, blind, 5)!!.contains("no se puede comprobar"))
    }

    @Test
    fun head_pide_Range_de_los_primeros_bytes() = runTest {
        val content = Random(3).nextBytes(4096)
        server.enqueue(
            MockResponse.Builder().code(206)
                .addHeader("Content-Range", "bytes 0-1023/4096")
                .body(Buffer().write(content, 0, 1024))
                .build(),
        )
        server.enqueue(MockResponse.Builder().code(200).body(Buffer().write(content)).build())
        val api = api(FakeTokens("t1"))

        assertArrayEquals(content.copyOf(1024), api.head("FILE1"))
        // Si el servidor ignora el Range, se leen igual solo los primeros bytes.
        assertArrayEquals(content.copyOf(16), api.head("FILE1", 16))

        val r = server.takeRequest()
        assertEquals("bytes=0-1023", r.headers["Range"])
        assertEquals("media", r.url.queryParameter("alt"))
        assertEquals("bytes=0-15", server.takeRequest().headers["Range"])
    }

    @Test
    fun parsea_cabeceras_de_rango() {
        val r = DriveRestApi.parseContentRange("bytes 5-9/10")!!
        assertEquals(5L, r.start)
        assertEquals(9L, r.end)
        assertEquals(10L, r.total)
        assertNull(DriveRestApi.parseContentRange("bytes 0-9/*")!!.total)
        assertNull(DriveRestApi.parseContentRange(null))
        assertEquals(10L, DriveRestApi.parseUnsatisfiedTotal("bytes */10"))
        assertNull(DriveRestApi.parseUnsatisfiedTotal("bytes 0-1/2"))
    }
}
