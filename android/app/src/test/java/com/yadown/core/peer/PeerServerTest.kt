package com.yadown.core.peer

import com.yadown.core.support.TestPeerIdentities
import com.yadown.core.transfer.XxHash64
import java.io.File
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerServerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: PeerServer
    private lateinit var client: OkHttpClient
    private lateinit var content: SharedContent
    private val payload = ByteArray(64) { (it * 3).toByte() }

    @Before
    fun setUp() {
        content = sharedContent(payload)
        server = PeerServer(TestPeerIdentities.a, contents = { listOf(content) }).also { it.start() }
        client = PeerTls.client(PeerTls.clientTlsForFingerprint(TestPeerIdentities.a.fingerprint))
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun sharedContent(bytes: ByteArray, id: String = "i1-0000"): SharedContent {
        val file = File(tmp.root, "$id.bin").apply { writeBytes(bytes) }
        return SharedContent(
            contentId = id,
            contentKey = ContentKey.of(SOURCE_URL)!!,
            size = bytes.size.toLong(),
            etag = "\"v1\"",
            lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
            fileName = "f.bin",
            mimeType = "application/octet-stream",
            file = file
        )
    }

    private fun url(path: String): String = "https://127.0.0.1:${server.port}$path"

    private fun request(path: String, headers: Map<String, String> = emptyMap()): okhttp3.Response {
        val builder = Request.Builder().url(url(path))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute()
    }

    @Test
    fun lookupReturnsManifestWithIdentity() {
        request(PeerPaths.lookup(content.contentKey)).use { response ->
            assertEquals(200, response.code)
            val manifest = PeerProtocol.json.decodeFromString<PeerManifest>(
                response.body!!.string()
            )
            assertEquals(content.contentId, manifest.contentId)
            assertEquals(content.contentKey, manifest.contentKey)
            assertEquals(content.size, manifest.size)
            assertEquals(content.etag, manifest.etag)
            assertEquals(content.lastModified, manifest.lastModified)
            assertEquals(1, manifest.version)
        }
    }

    @Test
    fun rangedRequestReturnsHashAndIdentityHeaders() {
        request(PeerPaths.content(content.contentId), mapOf("Range" to "bytes=10-29")).use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 10-29/64", response.header("Content-Range"))
            assertEquals("\"v1\"", response.header("ETag"))
            assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", response.header("Last-Modified"))
            val expected = java.lang.Long.toHexString(
                XxHash64.hash(payload.copyOfRange(10, 30))
            ).padStart(16, '0')
            assertEquals(expected, response.header(PeerHeaders.XX_HASH))
            assertArrayEquals(payload.copyOfRange(10, 30), response.body!!.bytes())
        }
    }

    @Test
    fun openEndedRangeUsesFileTail() {
        request(PeerPaths.content(content.contentId), mapOf("Range" to "bytes=60-")).use { response ->
            assertEquals(206, response.code)
            assertEquals("bytes 60-63/64", response.header("Content-Range"))
            assertArrayEquals(payload.copyOfRange(60, 64), response.body!!.bytes())
        }
    }

    @Test
    fun invalidRangesReturn416WithTotal() {
        val cases = listOf(
            emptyMap(),
            mapOf("Range" to "bytes=64-70"),
            mapOf("Range" to "bytes=10-4"),
            mapOf("Range" to "bytes=0-1,3-4")
        )
        for (headers in cases) {
            request(PeerPaths.content(content.contentId), headers).use { response ->
                assertEquals("headers=$headers", 416, response.code)
                assertEquals("bytes */64", response.header("Content-Range"))
            }
        }
    }

    @Test
    fun unknownContentAndKeyReturn404() {
        request(PeerPaths.content("missing-0001")).use { assertEquals(404, it.code) }
        request(PeerPaths.lookup("0123456789abcdef")).use { assertEquals(404, it.code) }
    }

    @Test
    fun wrongMethodAndPathAreRejected() {
        val post = Request.Builder()
            .url(url(PeerPaths.content(content.contentId)))
            .post(ByteArray(0).toRequestBody())
            .build()
        client.newCall(post).execute().use { assertEquals(405, it.code) }

        request("/v1/lookup/not-a-key").use { assertEquals(400, it.code) }
    }

    @Test
    fun contentWhoseFileLengthDiffersIsNotServed() {
        val shortFile = File(tmp.root, "short.bin").apply { writeBytes(ByteArray(10)) }
        val broken = content.copy(file = shortFile)
        val brokenServer = PeerServer(TestPeerIdentities.a, contents = { listOf(broken) }).also { it.start() }
        try {
            val brokenClient = PeerTls.client(
                PeerTls.clientTlsForFingerprint(TestPeerIdentities.a.fingerprint)
            )
            val lookup = Request.Builder()
                .url("https://127.0.0.1:${brokenServer.port}${PeerPaths.lookup(broken.contentKey)}")
                .build()
            brokenClient.newCall(lookup).execute().use { assertEquals(404, it.code) }
        } finally {
            brokenServer.close()
        }
    }

    @Test
    fun keepAliveServesMultipleRequests() {
        repeat(3) { index ->
            request(PeerPaths.content(content.contentId), mapOf("Range" to "bytes=$index-${index + 1}"))
                .use { response ->
                    assertEquals(206, response.code)
                    assertArrayEquals(payload.copyOfRange(index, index + 2), response.body!!.bytes())
                }
        }
    }

    @Test
    fun clientWithoutPinnedFingerprintCannotConnect() {
        val wrong = PeerTls.client(PeerTls.clientTlsForFingerprint(TestPeerIdentities.b.fingerprint))
        val request = Request.Builder()
            .url(url(PeerPaths.lookup(content.contentKey)))
            .build()
        val failure = runCatching { wrong.newCall(request).execute() }.exceptionOrNull()
        assertNotNull("错误指纹应拒绝连接", failure)
    }

    private companion object {
        const val SOURCE_URL = "https://example.com/file"
    }
}
