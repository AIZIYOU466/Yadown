package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
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
        override suspend fun probe(url: String) = ProbeResult(total, true, etag, null, null)

        override suspend fun readRange(url: String, start: Long, endInclusive: Long): RangePayload {
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

    private class InMemoryBitmapStore : BitmapStore {
        private val map = mutableMapOf<String, ChunkBitmap>()

        override fun save(intentId: String, bitmap: ChunkBitmap) {
            map[intentId] = ChunkBitmap.fromByteArray(bitmap.toByteArray())
        }

        override fun load(intentId: String, totalChunks: Int): ChunkBitmap =
            map[intentId] ?: ChunkBitmap(totalChunks)
    }

    private class FakeIntentDao : IntentDao {
        private val map = mutableMapOf<String, IntentEntity>()

        override suspend fun upsert(intent: IntentEntity) { map[intent.id] = intent }
        override suspend fun getById(id: String): IntentEntity? = map[id]
        override fun observeAll(): Flow<List<IntentEntity>> = flowOf(map.values.toList())
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
        val file = tmp.newFile()

        engine(source, bitmapStore, intentDao, chunkDao).download(
            intentId = "i1",
            url = "https://example.com/file",
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = 4
        )

        // 第一次保存的位图只有 chunk 0 完成，其余由引擎补完。
        assertTrue(bitmapStore.load("i1", 3).allDone())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
    }

    @Test
    fun invalidBodyLengthFailsWithDownloadException() = runTest {
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
        assertEquals(FailureCause.CHECKSUM_MISMATCH, (error as DownloadException).cause)
        assertEquals(1, chunkDao.countByState("i1", ChunkState.FAILED.name))
    }
}
