package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.support.CrashingBitmapStore
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.RoutingRangeSource
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P5.2 掉电恢复的确定性断点覆盖：写文件、位图、DB DONE 与完成校验。
 * 真机随机掉电压力测试仍在 P5 执行；这里验证的四条不变量是它的前置条件。
 */
class CrashRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun engine(
        source: RoutingRangeSource,
        bitmapStore: BitmapStore,
        intentDao: FakeIntentDao,
        chunkDao: FakeChunkDao
    ) = DownloadEngine(
        source = source,
        files = RandomAccessFileStore(),
        bitmaps = bitmapStore,
        intentDao = intentDao,
        chunkDao = chunkDao,
        ioDispatcher = Dispatchers.Unconfined
    )

    private suspend fun download(engine: DownloadEngine, file: java.io.File, total: Long) {
        engine.download(
            intentId = "i1",
            url = "https://example.com/f",
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 4
        )
    }

    @Test
    fun bitmapWriteFailureDoesNotFailDownload() = runTest {
        val total = 16L
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        val file = tmp.newFile()

        download(engine(source, CrashingBitmapStore(), intentDao, chunkDao), file, total)

        assertEquals((0 until total).map { it.toByte() }, file.readBytes().toList())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
    }

    @Test
    fun dbDoneWriteFailureRetriesAndEventuallyCompletes() = runTest {
        val total = 16L
        var failedOnce = false
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao(
            failBeforeDone = { chunk ->
                if (chunk.id == "i1-0" && !failedOnce) {
                    failedOnce = true
                    true
                } else {
                    false
                }
            }
        )
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        val file = tmp.newFile()

        download(engine(source, InMemoryBitmapStore(), intentDao, chunkDao), file, total)

        assertEquals((0 until total).map { it.toByte() }, file.readBytes().toList())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("失败分块必须重试", source.requests.count { it.second.first == 0L } >= 2)
    }

    @Test
    fun truncatedFileIsNeverReportedCompleted() = runTest {
        val total = 16L
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        chunkDao.chunks.replaceAll { _, chunk -> chunk.copy(state = ChunkState.DONE.name) }
        val bitmap = InMemoryBitmapStore().apply {
            save("i1", ChunkBitmap(4).apply { (0 until 4).forEach { markDone(it) } })
        }
        // DB 声称全部完成，但文件只落了一半：完成校验必须拒绝并退回待下。
        val file = tmp.newFile().apply { writeBytes(ByteArray(8)) }

        val result = runCatching {
            download(engine(source, bitmap, intentDao, chunkDao), file, total)
        }

        assertTrue("完成校验失败必须抛错", result.isFailure)
        assertEquals(IntentState.FAILED.name, intentDao.getById("i1")!!.state)
        assertTrue(
            "与缺失区间重叠的分块必须退回待下",
            chunkDao.countByState("i1", ChunkState.PENDING.name) > 0
        )
    }

    @Test
    fun bitmapDoneButDbPendingIsRedownloaded() = runTest {
        val total = 16L
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        val bitmap = InMemoryBitmapStore().apply {
            save("i1", ChunkBitmap(4).apply { (0 until 4).forEach { markDone(it) } })
        }
        val file = tmp.newFile()

        download(engine(source, bitmap, intentDao, chunkDao), file, total)

        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertEquals("位图独有、DB 未提交的分块必须重下", 4, source.requests.size)
    }

    @Test
    fun dbDoneWithCompleteFileSkipsTransfer() = runTest {
        val total = 16L
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, 4)
        chunkDao.chunks.replaceAll { _, chunk -> chunk.copy(state = ChunkState.DONE.name) }
        val bitmap = InMemoryBitmapStore().apply {
            save("i1", ChunkBitmap(4).apply { (0 until 4).forEach { markDone(it) } })
        }
        val file = tmp.newFile().apply {
            writeBytes(ByteArray(total.toInt()) { (it % 251).toByte() })
        }

        download(engine(source, bitmap, intentDao, chunkDao), file, total)

        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("已完整落盘的任务不应产生请求", source.requests.isEmpty())
    }
}
