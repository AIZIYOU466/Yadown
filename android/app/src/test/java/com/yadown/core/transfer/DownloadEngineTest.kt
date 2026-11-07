package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.net.SequentialResult
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.Segment
import com.yadown.core.semantic.ValueMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(
        private val total: Long,
        private val etag: String?,
        private val failLengthDelta: Int = 0
    ) : RangeSource {
        override suspend fun probe(url: String, linkId: String?) = ProbeResult(total, true, etag, null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            val requested = (endInclusive - start + 1).toInt()
            val len = (requested + failLengthDelta).coerceAtLeast(0)
            val bytes = ByteArray(len) { i -> ((start + i) % 251).toByte() }
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = etag,
                lastModified = null
            )
        }
    }

    /** 第一次 readRange 返回短 body（校验失败，触发裂解重试），之后全部正常。 */
    private class FlakySource(private val total: Long, private val etag: String?) : RangeSource {
        private var first = true

        override suspend fun probe(url: String, linkId: String?) = ProbeResult(total, true, etag, null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            val requested = (endInclusive - start + 1).toInt()
            if (first) {
                first = false
                return RangePayload(
                    bytes = ByteArray(requested - 1),
                    statusCode = 206,
                    contentRange = "bytes $start-$endInclusive/$total",
                    etag = etag,
                    lastModified = null
                )
            }
            val bytes = ByteArray(requested) { i -> ((start + i) % 251).toByte() }
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = etag,
                lastModified = null
            )
        }
    }

    private class SequentialSource(private val total: Long) : RangeSource {
        override suspend fun probe(url: String, linkId: String?) = ProbeResult(total, false, null, null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload = error("非 Range 源不应走 readRange")

        override suspend fun readSequential(
            url: String,
            start: Long,
            onProgress: (Long) -> Unit,
            onBytes: (ByteArray) -> Unit
        ): SequentialResult {
            val bytes = ByteArray(total.toInt()) { (it % 251).toByte() }
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(3, bytes.size - offset)
                onBytes(bytes.copyOfRange(offset, offset + size))
                offset += size
                onProgress(offset.toLong())
            }
            return SequentialResult(offset.toLong(), restarted = false)
        }
    }

    private class InMemoryBitmapStore : BitmapStore {
        private val map = mutableMapOf<String, ChunkBitmap>()

        override fun save(intentId: String, bitmap: ChunkBitmap) {
            map[intentId] = ChunkBitmap.fromByteArray(bitmap.toByteArray())
        }

        override fun load(intentId: String, totalChunks: Int): ChunkBitmap =
            map[intentId] ?: ChunkBitmap(totalChunks)

        override fun delete(intentId: String) {
            map.remove(intentId)
        }
    }

    private class FakeIntentDao : IntentDao {
        private val map = mutableMapOf<String, IntentEntity>()

        override suspend fun upsert(intent: IntentEntity) { map[intent.id] = intent }
        override suspend fun getById(id: String): IntentEntity? = map[id]
        override fun observeAll(): Flow<List<IntentEntity>> = flowOf(map.values.toList())
        override suspend fun getByState(state: String): List<IntentEntity> =
            map.values.filter { it.state == state }
        override suspend fun updateState(id: String, state: String, updatedAt: Long) {
            map[id] = map[id]?.copy(state = state, updatedAt = updatedAt) ?: return
        }
        override suspend fun delete(id: String) { map.remove(id) }
    }

    private class FakeChunkDao : ChunkDao {
        private val map = mutableMapOf<String, ChunkEntity>()

        override suspend fun upsert(chunk: ChunkEntity) { map[chunk.id] = chunk }
        override suspend fun upsertAll(chunks: List<ChunkEntity>) { chunks.forEach { map[it.id] = it } }

        override suspend fun getByIntent(intentId: String): List<ChunkEntity> =
            map.values.filter { it.intentId == intentId }.sortedBy { it.startByte }

        override fun observeByIntent(intentId: String): Flow<List<ChunkEntity>> =
            flowOf(map.values.filter { it.intentId == intentId }.sortedBy { it.startByte })

        override suspend fun updateProgress(
            id: String,
            state: String,
            retryCount: Int,
            startedAt: Long?,
            completedAt: Long?,
            checksum: Long?,
            failureChainJson: String?
        ) {
            map[id] = map[id]?.copy(
                state = state,
                retryCount = retryCount,
                startedAt = startedAt,
                completedAt = completedAt,
                checksum = checksum,
                failureChainJson = failureChainJson
            ) ?: return
        }

        override suspend fun countByState(intentId: String, state: String): Int =
            map.values.count { it.intentId == intentId && it.state == state }

        override suspend fun deleteByIntent(intentId: String) {
            map.entries.removeIf { it.value.intentId == intentId }
        }

        override suspend fun updateState(id: String, state: String) {
            map[id] = map[id]?.copy(state = state) ?: return
        }

        override suspend fun replaceWithChildren(oldId: String, childA: ChunkEntity, childB: ChunkEntity) {
            map[oldId] = map[oldId]?.copy(state = ChunkState.INVALIDATED.name) ?: return
            map[childA.id] = childA
            map[childB.id] = childB
        }
    }

    private fun intent(total: Long, etag: String?) = IntentEntity(
        id = "i1",
        contentId = "content",
        sourceUrl = "https://example.com/file",
        totalSize = total,
        mimeType = "application/octet-stream",
        etag = etag,
        lastModified = null,
        rangeSupported = true,
        valueMapJson = null,
        availabilityContractJson = null,
        state = IntentState.PENDING.name,
        createdAt = 0,
        updatedAt = 0
    )

    private suspend fun seedChunks(chunkDao: ChunkDao, total: Long, chunkSize: Long) {
        chunkDao.upsertAll(
            DownloadPlan.create(total, chunkSize).chunks.map { chunk ->
                ChunkEntity(
                    id = "i1-${chunk.index}",
                    intentId = "i1",
                    startByte = chunk.start,
                    endByte = chunk.endInclusive,
                    state = ChunkState.PENDING.name,
                    retryCount = 0,
                    checksum = null,
                    connectionFingerprint = null,
                    failureChainJson = null,
                    startedAt = null,
                    completedAt = null
                )
            }
        )
    }

    private fun engine(
        source: RangeSource,
        bitmapStore: BitmapStore,
        intentDao: IntentDao,
        chunkDao: ChunkDao
    ) = DownloadEngine(
        source = source,
        files = RandomAccessFileStore(),
        bitmaps = bitmapStore,
        intentDao = intentDao,
        chunkDao = chunkDao,
        ioDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun downloadsAllChunksAndCompletes() = runTest {
        val total = 10L
        val source = FakeSource(total, etag = "\"v1\"")
        val bitmapStore = InMemoryBitmapStore()
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        val file = tmp.newFile()
        val progress = mutableListOf<DownloadProgress>()

        engine(source, bitmapStore, intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 4,
            onProgress = { progress += it }
        )

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).map { it.toByte() }, file.readBytes().toList())
        assertTrue(bitmapStore.load("i1", 3).allDone())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertEquals(total, progress.last().downloadedBytes)
        assertEquals(3, chunkDao.countByState("i1", ChunkState.DONE.name))
    }

    @Test
    fun resumesAndSkipsAlreadyDoneChunks() = runTest {
        val total = 10L
        val source = FakeSource(total, etag = "\"v1\"")
        val bitmapStore = InMemoryBitmapStore().apply {
            save("i1", ChunkBitmap(3).apply { markDone(0) })
        }
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        // 已完成分块必须在 DB 与文件里同时成立；位图只是加速索引，不是唯一真相。
        chunkDao.upsertAll(
            chunkDao.getByIntent("i1").map {
                if (it.id == "i1-0") it.copy(state = ChunkState.DONE.name, checksum = 0L) else it
            }
        )
        val file = tmp.newFile().apply { writeBytes(byteArrayOf(0, 1, 2, 3)) }

        engine(source, bitmapStore, intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 4
        )

        assertTrue(bitmapStore.load("i1", 3).allDone())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
    }

    @Test
    fun nonRangeSourceUsesSequentialDownload() = runTest {
        val total = 10L
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, null).copy(rangeSupported = false))
        val file = tmp.newFile()
        val progress = mutableListOf<DownloadProgress>()

        engine(SequentialSource(total), InMemoryBitmapStore(), intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 1,
            chunkSize = 4,
            onProgress = { progress += it }
        )

        assertEquals(
            listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).map { it.toByte() },
            file.readBytes().toList()
        )
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertEquals(total, progress.last().downloadedBytes)
        assertEquals(0, chunkDao.getByIntent("i1").size)
    }

    @Test
    fun invalidatedChunksWithNoBytesAreNotCompleted() = runTest {
        val total = 10L
        val source = FakeSource(total, etag = "\"v1\"")
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        chunkDao.upsertAll(
            chunkDao.getByIntent("i1").map {
                it.copy(state = ChunkState.INVALIDATED.name, retryCount = 3)
            }
        )
        val file = tmp.newFile()

        val error = runCatching {
            engine(source, InMemoryBitmapStore(), intentDao, chunkDao).download(
                intentId = "i1",
                url = "https://example.com/file",
                destPath = file.absolutePath,
                concurrency = 1,
                chunkSize = 4
            )
        }.exceptionOrNull()

        assertTrue("expected INCOMPLETE, got $error", error is DownloadException)
        assertEquals(FailureCause.INCOMPLETE, (error as DownloadException).failureCause)
        assertEquals(IntentState.FAILED.name, intentDao.getById("i1")!!.state)
    }

    @Test
    fun persistentBodyLengthMismatchFailsIntent() = runTest {
        val source = FakeSource(10, etag = "\"v1\"", failLengthDelta = 1)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(10, "\"v1\""))
        seedChunks(chunkDao, 10, 4)
        val file = tmp.newFile()

        val error = runCatching {
            engine(source, InMemoryBitmapStore(), intentDao, chunkDao).download(
                intentId = "i1",
                url = "https://example.com/file",
                destPath = file.absolutePath,
                concurrency = 1,
                chunkSize = 4
            )
        }.exceptionOrNull()

        assertTrue("expected DownloadException, got $error", error is DownloadException)
        assertEquals(FailureCause.CHECKSUM_MISMATCH, (error as DownloadException).failureCause)
        assertEquals(IntentState.FAILED.name, intentDao.getById("i1")!!.state)
        assertTrue(
            "存在 INVALIDATED 分块",
            chunkDao.countByState("i1", ChunkState.INVALIDATED.name) > 0
        )
    }

    @Test
    fun transientFailureSplitsChunkAndStillCompletes() = runTest {
        val total = 10L
        val source = FlakySource(total, etag = "\"v1\"")
        val bitmapStore = InMemoryBitmapStore()
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        val file = tmp.newFile()

        engine(source, bitmapStore, intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 1,
            chunkSize = 4
        )

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9).map { it.toByte() }, file.readBytes().toList())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue(
            "原 chunk 被裂解标记 INVALIDATED",
            chunkDao.countByState("i1", ChunkState.INVALIDATED.name) >= 1
        )
        assertTrue(
            "裂解后的子分块应完成",
            chunkDao.countByState("i1", ChunkState.DONE.name) >= 3
        )
    }

    /** 记录 Range 请求顺序，用于验证语义优先调度。 */
    private class RecordingSource(private val total: Long, private val etag: String?) : RangeSource {
        val requests = mutableListOf<LongRange>()

        override suspend fun probe(url: String, linkId: String?) = ProbeResult(total, true, etag, null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            synchronized(requests) { requests += start..endInclusive }
            val len = (endInclusive - start + 1).toInt()
            return RangePayload(
                bytes = ByteArray(len) { i -> ((start + i) % 251).toByte() },
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = etag,
                lastModified = null
            )
        }
    }

    @Test
    fun highValueChunkIsRequestedFirst() = runTest {
        val total = 10L
        val source = RecordingSource(total, etag = "\"v1\"")
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        val file = tmp.newFile()

        // 末尾区间（8..9）权重最高，应最先下发。
        val valueMap = ValueMap.fromSegments(
            total,
            listOf(Segment(0, 8, 1.0), Segment(8, 10, 99.0))
        )
        engine(source, InMemoryBitmapStore(), intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 1,
            chunkSize = 4,
            valueMap = valueMap
        )

        assertEquals(8L..9L, source.requests.first())
    }

    @Test
    fun availabilityFiresOnceWhenRequiredRangeDone() = runTest {
        val total = 10L
        val source = RecordingSource(total, etag = "\"v1\"")
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(intent(total, "\"v1\""))
        seedChunks(chunkDao, total, 4)
        val file = tmp.newFile()
        var availableCount = 0

        val contract = AvailabilityContract(
            capability = AvailabilityContract.Capability.PLAYABLE,
            readySegments = listOf(Segment(4, 8, 100.0))
        )
        engine(source, InMemoryBitmapStore(), intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 1,
            chunkSize = 4,
            availability = contract,
            onAvailable = { availableCount++ }
        )

        assertEquals("解锁回调只应触发一次", 1, availableCount)
    }
}