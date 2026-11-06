package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.OkHttpRangeSource
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.HttpResponse
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.TestHttpServer
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** P5.1 端到端：真实 OkHttp + 本地 Range server + 真实引擎，覆盖单源与多源完整流程。 */
class EndToEndDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<TestHttpServer>()

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    private fun serve(payload: ByteArray, etag: String): TestHttpServer {
        val total = payload.size.toLong()
        val server = TestHttpServer { request ->
            val range = parseRange(request.headers["range"])
            if (range == null) {
                HttpResponse(
                    code = 200,
                    headers = mapOf("ETag" to etag),
                    body = payload
                )
            } else if (range.first >= total) {
                HttpResponse(
                    code = 416,
                    headers = mapOf("Content-Range" to "bytes */$total")
                )
            } else {
                val end = minOf(range.last, total - 1)
                HttpResponse(
                    code = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes ${range.first}-$end/$total",
                        "ETag" to etag
                    ),
                    body = payload.copyOfRange(range.first.toInt(), end.toInt() + 1)
                )
            }
        }
        servers += server
        return server
    }

    private fun parseRange(header: String?): LongRange? {
        if (header == null) return null
        val match = Regex("bytes=(\\d+)-(\\d+)").find(header) ?: return null
        return match.groupValues[1].toLong()..match.groupValues[2].toLong()
    }

    private fun engine() = DownloadEngine(
        source = OkHttpRangeSource(
            OkHttpClient.Builder()
                .readTimeout(5, TimeUnit.SECONDS)
                .build()
        ),
        files = RandomAccessFileStore(),
        bitmaps = InMemoryBitmapStore(),
        intentDao = intentDao,
        chunkDao = chunkDao
    )

    private lateinit var intentDao: FakeIntentDao
    private lateinit var chunkDao: FakeChunkDao

    private suspend fun prepare(total: Long, etag: String, chunkSize: Long) {
        intentDao = FakeIntentDao()
        chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, etag))
        seedTestChunks(chunkDao, total, chunkSize)
    }

    private fun payload(size: Int): ByteArray = ByteArray(size) { (it * 31 % 251).toByte() }

    @Test
    fun downloadsWholeFileThroughRealHttp() = runBlocking {
        val body = payload(64 * 1024)
        val etag = "\"e2e-v1\""
        val server = serve(body, etag)
        prepare(body.size.toLong(), etag, 16 * 1024)
        val file = tmp.newFile()

        engine().download(
            intentId = "i1",
            url = server.baseUrl,
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 16 * 1024
        )

        assertArrayEquals(body, file.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        val ranges = server.requests.mapNotNull { parseRange(it.headers["range"]) }
        assertEquals(4, ranges.size)
        assertEquals(body.size.toLong(), ranges.sumOf { it.last - it.first + 1 })
    }

    @Test
    fun downloadsAcrossTwoVerifiedMirrors() = runBlocking {
        val body = payload(64 * 1024)
        val etag = "\"e2e-v2\""
        val primary = serve(body, etag)
        val mirror = serve(body, etag)
        prepare(body.size.toLong(), etag, 16 * 1024)
        val file = tmp.newFile()

        fun route(server: TestHttpServer, kind: SourceKind) = SourceRoute(
            endpoint = SourceEndpoint(server.baseUrl, kind),
            score = 1.0,
            reliability = 1.0,
            throughputScore = 1.0,
            connectionLimit = 2,
            usable = true
        )

        engine().download(
            intentId = "i1",
            url = primary.baseUrl,
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 16 * 1024,
            routes = listOf(
                route(primary, SourceKind.ORIGIN),
                route(mirror, SourceKind.MIRROR)
            )
        )

        assertArrayEquals(body, file.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("主源应参与传输", primary.requests.isNotEmpty())
        assertTrue("镜像应参与传输", mirror.requests.isNotEmpty())
    }
}
