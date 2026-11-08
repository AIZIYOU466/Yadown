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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 分阶段拓扑：关键区间阶段只放一个分块且走单连接通路，落地后再转并发放大吞吐。
 * 依据真机同文件数据：REUSE 首块 1455–1842ms vs MULTI 3380–3917ms，而稳态 MULTI 更高。
 */
class CriticalPhaseDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val chunkSize = 1024L
    private val total = chunkSize * 4

    @Test
    fun firstChunkRunsAloneOnCriticalSourceThenBulkRunsConcurrent() = runBlocking {
        val critical = RecordingSource(total, "critical")
        val bulk = RecordingSource(total, "bulk")
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val dest = tmp.newFile()

        DownloadEngine(
            source = bulk,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao,
            ioDispatcher = Dispatchers.IO
        ).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = dest.absolutePath,
            concurrency = 4,
            chunkSize = chunkSize,
            criticalSource = critical
        )

        assertEquals("关键块应恰好走一次单连接通路", listOf(0L), critical.starts)
        assertEquals("关键阶段不得并发", 1, critical.maxConcurrent.get())
        assertEquals("其余分块走并发源", 3, bulk.starts.size)
        assertTrue("并发阶段应真的并发", bulk.maxConcurrent.get() >= 2)
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertEquals(4, chunkDao.getByIntent("i1").count { it.state == ChunkState.DONE.name })
    }

    @Test
    fun withoutCriticalSourceAllChunksUseSingleSource() = runBlocking {
        val bulk = RecordingSource(total, "bulk")
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val dest = tmp.newFile()

        DownloadEngine(
            source = bulk,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao,
            ioDispatcher = Dispatchers.IO
        ).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = dest.absolutePath,
            concurrency = 4,
            chunkSize = chunkSize
        )

        assertEquals(4, bulk.starts.size)
    }

    /** 记录被请求的起点与并发峰值。 */
    private class RecordingSource(
        private val total: Long,
        @Suppress("unused") private val label: String
    ) : RangeSource {
        val starts = mutableListOf<Long>()
        val maxConcurrent = AtomicInteger()
        private val active = AtomicInteger()

        override suspend fun probe(url: String, linkId: String?): ProbeResult =
            ProbeResult(total, true, "\"v1\"", null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            synchronized(starts) { starts += start }
            maxConcurrent.updateAndGet { maxOf(it, active.incrementAndGet()) }
            try {
                Thread.sleep(20)
                val len = (endInclusive - start + 1).toInt()
                return RangePayload(
                    bytes = ByteArray(len) { i -> ((start + i) % 251).toByte() },
                    statusCode = 206,
                    contentRange = "bytes $start-$endInclusive/$total",
                    etag = "\"v1\"",
                    lastModified = null
                )
            } finally {
                active.decrementAndGet()
            }
        }
    }
}
