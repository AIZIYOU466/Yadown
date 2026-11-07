package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import com.yadown.core.telemetry.TransferTelemetry
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P1：慢块在传输过程中被监控发现，取消并按速率自适应裂解；子块插队立即执行。
 * 慢源只在第一个 1MB 块上慢，裂解后的子块立即完成，用于验证事件驱动路径。
 */
class DownloadEngineSlowSplitTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val total = 1024L * 1024
    private val chunkSize = 1024L * 1024
    private val url = "https://example.com/file"

    private class SlowFirstChunkSource(private val total: Long) : RangeSource {
        private val calls = AtomicInteger()

        @Volatile
        private var firstChunkSlow = true

        val requestCount: Int get() = calls.get()

        override suspend fun probe(url: String, linkId: String?) =
            ProbeResult(total, true, "\"v1\"", null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload = readRangeChecked(url, start, endInclusive, null, onProgress, linkId)

        override suspend fun readRangeChecked(
            url: String,
            start: Long,
            endInclusive: Long,
            totalSize: Long?,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls.incrementAndGet()
            val length = (endInclusive - start + 1).toInt()
            val slow = synchronized(this) {
                if (firstChunkSlow && start == 0L) {
                    firstChunkSlow = false
                    true
                } else {
                    false
                }
            }
            if (slow) {
                var sent = 0
                while (sent < length) {
                    delay(20)
                    // 约 200KB/s，低于 256KB/s 的低速阈值，触发 512KB 粒度裂解。
                    sent = minOf(length, sent + 4 * 1024)
                    onProgress(sent.toLong())
                }
            } else {
                onProgress(length.toLong())
            }
            val bytes = ByteArray(length) { offset -> ((start + offset) % 251).toByte() }
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = "\"v1\"",
                lastModified = null
            )
        }
    }

    @Before
    fun setUp() {
        TransferTelemetry.reset()
    }

    @After
    fun tearDown() {
        TransferTelemetry.reset()
    }

    @Test
    fun slowChunkIsSplitAndChildrenRunImmediately() = runBlocking {
        val source = SlowFirstChunkSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val dest = tmp.newFile()

        DownloadEngine(
            source = source,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao,
            ioDispatcher = Dispatchers.Unconfined,
            monitor = ChunkThroughputMonitor(
                windowMs = 100,
                slowWindows = 2,
                minRateBytesPerSec = 1024.0 * 1024
            )
        ).download(
            intentId = "i1",
            url = url,
            destPath = dest.absolutePath,
            concurrency = 1,
            chunkSize = chunkSize
        )

        val expected = (0 until total.toInt()).map { (it % 251).toByte() }.toByteArray()
        assertArrayEquals(expected, dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)

        val chunks = chunkDao.getByIntent("i1")
        assertTrue("慢块应被裂解出子块", chunks.size >= 3)
        assertTrue(
            "父块应被置为 INVALIDATED",
            chunks.any { it.state == ChunkState.INVALIDATED.name }
        )
        assertEquals(2, chunks.count { it.state == ChunkState.DONE.name })

        val snapshot = TransferTelemetry.snapshot()
        assertEquals(1L, snapshot.slowSplits)
        assertEquals(0L, snapshot.stalls)
        assertEquals(0L, snapshot.chunksFailed)
        assertTrue("请求数应控制在父块 + 两个子块", source.requestCount <= 3)
    }
}
