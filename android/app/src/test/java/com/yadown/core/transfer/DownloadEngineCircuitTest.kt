package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import com.yadown.core.telemetry.TransferTelemetry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
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

/** P2：429 不裂解放大；熔断后换源；连接复用计数可观测。 */
class DownloadEngineCircuitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val total = 16L
    private val chunkSize = 4L
    private val urlA = "https://a.example.com/f"
    private val urlB = "https://b.example.com/f"

    private class RateLimitedSource(private val total: Long) : RangeSource {
        val calls = AtomicInteger()

        override suspend fun probe(url: String, linkId: String?) =
            ProbeResult(total, true, "\"v1\"", null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls.incrementAndGet()
            return RangePayload(
                bytes = ByteArray(0),
                statusCode = 429,
                contentRange = null,
                etag = null,
                lastModified = null,
                retryAfterMs = 1_000
            )
        }
    }

    private class GoodSource(private val total: Long) : RangeSource {
        val calls = AtomicInteger()

        override suspend fun probe(url: String, linkId: String?) =
            ProbeResult(total, true, "\"v1\"", null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls.incrementAndGet()
            val bytes = ByteArray((endInclusive - start + 1).toInt()) { offset ->
                ((start + offset) % 251).toByte()
            }
            onProgress(bytes.size.toLong())
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = "\"v1\"",
                lastModified = null
            )
        }
    }

    private fun route(url: String) = SourceRoute(
        endpoint = SourceEndpoint(url, SourceKind.ORIGIN),
        score = 1.0,
        reliability = 1.0,
        throughputScore = 1.0,
        connectionLimit = 2,
        usable = true
    )

    private fun engine(source: RangeSource, intentDao: FakeIntentDao, chunkDao: FakeChunkDao) =
        DownloadEngine(
            source = source,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao,
            ioDispatcher = Dispatchers.Unconfined
        )

    private val intentDao = FakeIntentDao()
    private val chunkDao = FakeChunkDao()

    @Before
    fun setUp() {
        TransferTelemetry.reset()
        runBlocking {
            intentDao.upsert(testIntent(total, "\"v1\""))
            seedTestChunks(chunkDao, total, chunkSize)
        }
    }

    @After
    fun tearDown() {
        TransferTelemetry.reset()
    }

    @Test
    fun rateLimitedDoesNotSplit() = runBlocking {
        val source = RateLimitedSource(total)
        val dest = tmp.newFile()

        val failure = runCatching {
            engine(source, intentDao, chunkDao).download(
                intentId = "i1",
                url = urlA,
                destPath = dest.absolutePath,
                concurrency = 1,
                chunkSize = chunkSize,
                routes = listOf(route(urlA))
            )
        }.exceptionOrNull()

        assertNotNull("429 重试预算耗尽应失败", failure)
        assertEquals(IntentState.FAILED.name, intentDao.getById("i1")!!.state)
        assertEquals("429 不应裂解分块", 4, chunkDao.getByIntent("i1").size)
        assertTrue(TransferTelemetry.snapshot().failureByCause[FailureCause.RATE_LIMITED]!! >= 1)
    }

    @Test
    fun openCircuitSwitchesToAnotherSource() = runBlocking {
        val limited = RateLimitedSource(total)
        val good = GoodSource(total)
        val routing = object : RangeSource {
            override suspend fun probe(url: String, linkId: String?) =
                ProbeResult(total, true, "\"v1\"", null, null)

            override suspend fun readRange(
                url: String,
                start: Long,
                endInclusive: Long,
                onProgress: (Long) -> Unit,
                linkId: String?
            ): RangePayload =
                if (url == urlA) {
                    limited.readRange(url, start, endInclusive, onProgress, linkId)
                } else {
                    good.readRange(url, start, endInclusive, onProgress, linkId)
                }
        }
        val dest = tmp.newFile()

        engine(routing, intentDao, chunkDao).download(
            intentId = "i1",
            url = urlA,
            destPath = dest.absolutePath,
            concurrency = 1,
            chunkSize = chunkSize,
            routes = listOf(route(urlA), route(urlB))
        )

        val expected = (0 until total.toInt()).map { (it % 251).toByte() }.toByteArray()
        assertArrayEquals(expected, dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertEquals("熔断源只应被尝试一次", 1, limited.calls.get())
        assertEquals("剩余分块应全部由健康源完成", 4, good.calls.get())
    }
}
