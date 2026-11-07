package com.yadown.core.peer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.OkHttpRangeSource
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.TestPeerIdentities
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.IntentState
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T4.3 端到端：真实 TLS peer 服务端 + 指纹固定客户端 + 真实下载引擎。
 * 覆盖 peer 作为唯一源，以及 peer 与主源混合调度两条路径。
 */
class PeerDownloadIntegrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = ByteArray(16) { (it * 7).toByte() }

    private lateinit var server: PeerServer
    private lateinit var registry: PeerClientRegistry
    private lateinit var source: PeerRangeSource
    private lateinit var intentDao: FakeIntentDao
    private lateinit var chunkDao: FakeChunkDao

    @Before
    fun setUp() {
        val content = SharedContent(
            contentId = "peer-content-1",
            contentKey = ContentKey.of(SOURCE_URL)!!,
            size = payload.size.toLong(),
            etag = "\"v1\"",
            lastModified = null,
            fileName = "f.bin",
            mimeType = "application/octet-stream",
            file = File(tmp.root, "f.bin").apply { writeBytes(payload) }
        )
        server = PeerServer(TestPeerIdentities.a, contents = { listOf(content) }).also { it.start() }
        registry = PeerClientRegistry()
        registry.trust("127.0.0.1", server.port, TestPeerIdentities.a.fingerprint)
        source = PeerRangeSource(
            delegate = OkHttpRangeSource(OkHttpRangeSource.createClient()),
            peers = registry
        )
        intentDao = FakeIntentDao()
        chunkDao = FakeChunkDao()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun peerUrl(): String = PeerUrls.content("127.0.0.1", server.port, "peer-content-1")

    private fun route(url: String, kind: SourceKind) = SourceRoute(
        endpoint = SourceEndpoint(url, kind),
        score = 1.0,
        reliability = 1.0,
        throughputScore = 1.0,
        connectionLimit = 2,
        usable = true
    )

    private fun engine(): DownloadEngine = DownloadEngine(
        source = source,
        files = RandomAccessFileStore(),
        bitmaps = InMemoryBitmapStore(),
        intentDao = intentDao,
        chunkDao = chunkDao
    )

    @Test
    fun completesDownloadWithPeerAsOnlySource() = runBlocking {
        intentDao.upsert(testIntent(payload.size.toLong(), "\"v1\""))
        seedTestChunks(chunkDao, payload.size.toLong(), chunkSize = 4)
        val dest = File(tmp.root, "out.bin")

        engine().download(
            intentId = "i1",
            url = peerUrl(),
            destPath = dest.absolutePath,
            concurrency = 2,
            chunkSize = 4,
            routes = listOf(route(peerUrl(), SourceKind.PEER))
        )

        assertArrayEquals(payload, dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
    }

    @Test
    fun peerAndOriginShareChunks() = runBlocking {
        intentDao.upsert(testIntent(payload.size.toLong(), "\"v1\""))
        seedTestChunks(chunkDao, payload.size.toLong(), chunkSize = 4)
        val dest = File(tmp.root, "out.bin")
        val origin = "https://origin.example.com/f"
        val originSource = HybridRangeSource(origin, payload)
        val engine = DownloadEngine(
            source = PeerRangeSource(
                delegate = originSource,
                peers = registry
            ),
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao
        )

        engine.download(
            intentId = "i1",
            url = origin,
            destPath = dest.absolutePath,
            concurrency = 2,
            chunkSize = 4,
            routes = listOf(
                route(origin, SourceKind.ORIGIN),
                route(peerUrl(), SourceKind.PEER)
            )
        )

        assertArrayEquals(payload, dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("主源应被调度到", originSource.requests > 0)
    }

    @Test
    fun corruptedPeerChunkFailsWithChecksumMismatch() = runBlocking {
        intentDao.upsert(testIntent(payload.size.toLong(), "\"v1\""))
        seedTestChunks(chunkDao, payload.size.toLong(), chunkSize = 4)
        val dest = File(tmp.root, "out.bin")
        val tamperRegistry = PeerClientRegistry { fingerprint ->
            PeerTls.client(PeerTls.clientTlsForFingerprint(fingerprint)).newBuilder()
                .addInterceptor { chain ->
                    val response = chain.proceed(chain.request())
                    response.newBuilder().header(PeerHeaders.XX_HASH, "0000000000000001").build()
                }
                .build()
        }
        tamperRegistry.trust("127.0.0.1", server.port, TestPeerIdentities.a.fingerprint)
        val tampering = PeerRangeSource(
            delegate = OkHttpRangeSource(OkHttpRangeSource.createClient()),
            peers = tamperRegistry
        )
        val engine = DownloadEngine(
            source = tampering,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao
        )

        val failure = runCatching {
            engine.download(
                intentId = "i1",
                url = peerUrl(),
                destPath = dest.absolutePath,
                concurrency = 1,
                chunkSize = 4,
                routes = listOf(route(peerUrl(), SourceKind.PEER))
            )
        }.exceptionOrNull()

        assertNotNull("篡改数据必须失败", failure)
        assertEquals(IntentState.FAILED.name, intentDao.getById("i1")!!.state)
    }

    private companion object {
        const val SOURCE_URL = "https://example.com/file"
    }
}

/** 主源：按区间返回同一份字节，用于验证 peer 与主源混合调度。 */private class HybridRangeSource(
    private val expectedUrl: String,
    private val payload: ByteArray
) : com.yadown.core.net.RangeSource {

    var requests = 0
        private set

    override suspend fun probe(url: String) = com.yadown.core.net.ProbeResult(
        payload.size.toLong(),
        true,
        "\"v1\"",
        null,
        "application/octet-stream"
    )

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit
    ): com.yadown.core.net.RangePayload =
        readRangeChecked(url, start, endInclusive, null, onProgress)

    override suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit
    ): com.yadown.core.net.RangePayload {
        require(url == expectedUrl) { "意外的主源 URL: $url" }
        requests++
        val bytes = payload.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
        onProgress(bytes.size.toLong())
        return com.yadown.core.net.RangePayload(
            bytes = bytes,
            statusCode = 206,
            contentRange = "bytes $start-$endInclusive/${payload.size}",
            etag = "\"v1\"",
            lastModified = null
        )
    }
}

