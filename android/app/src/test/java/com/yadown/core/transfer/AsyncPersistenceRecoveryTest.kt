package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.RoutingRangeSource
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P3 不变式：文件可能已被异步写盘完整写入，但 DB 未提交时不能被信任。
 * 重建引擎后必须重下，而不是因为文件长度正确就判完成。
 */
class AsyncPersistenceRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun completeFileWithPendingDbIsRedownloaded() = runBlocking {
        val total = 16L
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        // 模拟「写盘已落、DB 仍 PENDING」的掉电现场。
        val file = tmp.newFile().apply {
            writeBytes(ByteArray(total.toInt()) { (it % 251).toByte() })
        }

        DownloadEngine(
            source = source,
            files = RandomAccessFileStore(),
            bitmaps = InMemoryBitmapStore(),
            intentDao = intentDao,
            chunkDao = chunkDao,
            ioDispatcher = Dispatchers.Unconfined
        ).download(
            intentId = "i1",
            url = "https://example.com/f",
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 4
        )

        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("DB 未提交的块必须重下", source.requests.isNotEmpty())
        assertEquals(4, source.requests.size)
    }
}
