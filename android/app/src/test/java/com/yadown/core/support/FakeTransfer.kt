package com.yadown.core.support

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.net.SequentialResult
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.ChunkBitmap
import com.yadown.core.transfer.ChunkState
import com.yadown.core.transfer.DownloadPlan
import com.yadown.core.transfer.IntentState
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class InMemoryBitmapStore : BitmapStore {
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

/** 模拟位图写入失败：位图只是索引，失败不应让已落盘的分块失败。 */
class CrashingBitmapStore : BitmapStore {
    private val delegate = InMemoryBitmapStore()

    override fun save(intentId: String, bitmap: ChunkBitmap) {
        throw IOException("模拟位图写入失败")
    }

    override fun load(intentId: String, totalChunks: Int): ChunkBitmap =
        delegate.load(intentId, totalChunks)

    override fun delete(intentId: String) {
        delegate.delete(intentId)
    }
}

class FakeIntentDao : IntentDao {
    val intents = mutableMapOf<String, IntentEntity>()

    override suspend fun upsert(intent: IntentEntity) {
        intents[intent.id] = intent
    }

    override suspend fun getById(id: String): IntentEntity? = intents[id]

    override fun observeAll(): Flow<List<IntentEntity>> = flowOf(intents.values.toList())

    override suspend fun getByState(state: String): List<IntentEntity> =
        intents.values.filter { it.state == state }

    override suspend fun updateState(id: String, state: String, updatedAt: Long) {
        intents[id] = intents[id]?.copy(state = state, updatedAt = updatedAt) ?: return
    }

    override suspend fun delete(id: String) {
        intents.remove(id)
    }
}

class FakeChunkDao(
    /** 返回 true 时在 DB 写入 DONE 之前抛异常，模拟掉电/写失败。 */
    private val failBeforeDone: (ChunkEntity) -> Boolean = { false }
) : ChunkDao {
    val chunks = mutableMapOf<String, ChunkEntity>()

    override suspend fun upsert(chunk: ChunkEntity) {
        chunks[chunk.id] = chunk
    }

    override suspend fun upsertAll(chunks: List<ChunkEntity>) {
        chunks.forEach { this.chunks[it.id] = it }
    }

    override suspend fun getByIntent(intentId: String): List<ChunkEntity> =
        chunks.values.filter { it.intentId == intentId }.sortedBy { it.startByte }

    override fun observeByIntent(intentId: String): Flow<List<ChunkEntity>> =
        flowOf(getByIntentBlocking(intentId))

    private fun getByIntentBlocking(intentId: String): List<ChunkEntity> =
        chunks.values.filter { it.intentId == intentId }.sortedBy { it.startByte }

    override suspend fun updateProgress(
        id: String,
        state: String,
        retryCount: Int,
        startedAt: Long?,
        completedAt: Long?,
        checksum: Long?,
        failureChainJson: String?
    ) {
        val current = chunks[id] ?: return
        if (state == ChunkState.DONE.name && failBeforeDone(current)) {
            throw IOException("模拟 DB 写入 DONE 失败")
        }
        chunks[id] = current.copy(
            state = state,
            retryCount = retryCount,
            startedAt = startedAt,
            completedAt = completedAt,
            checksum = checksum,
            failureChainJson = failureChainJson
        )
    }

    override suspend fun countByState(intentId: String, state: String): Int =
        chunks.values.count { it.intentId == intentId && it.state == state }

    override suspend fun deleteByIntent(intentId: String) {
        chunks.entries.removeIf { it.value.intentId == intentId }
    }

    override suspend fun updateState(id: String, state: String) {
        chunks[id] = chunks[id]?.copy(state = state) ?: return
    }

    override suspend fun replaceWithChildren(
        oldId: String,
        childA: ChunkEntity,
        childB: ChunkEntity
    ) {
        chunks[oldId] = chunks[oldId]?.copy(state = ChunkState.INVALIDATED.name) ?: return
        chunks[childA.id] = childA
        chunks[childB.id] = childB
    }
}

/**
 * 按 URL 路由的测试源：每个 URL 都能返回同一份字节，
 * 也可对指定 URL 注入一次短 body 失败，用于验证换源重试。
 */
class RoutingRangeSource(
    private val total: Long,
    private val etag: String? = "\"v1\"",
    private val byteAt: (Long) -> Byte = { (it % 251).toByte() }
) : RangeSource {

    private val lock = Any()
    private val requested = mutableListOf<Pair<String, LongRange>>()
    private val failuresLeft = mutableMapOf<String, Int>()

    val requests: List<Pair<String, LongRange>>
        get() = synchronized(lock) { requested.toList() }

    fun failOnce(url: String) {
        synchronized(lock) { failuresLeft[url] = (failuresLeft[url] ?: 0) + 1 }
    }

    override suspend fun probe(url: String): ProbeResult =
        ProbeResult(total, true, etag, null, "application/octet-stream")

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit
    ): RangePayload {
        val shouldFail = synchronized(lock) {
            requested += url to (start..endInclusive)
            val remaining = failuresLeft[url] ?: 0
            if (remaining > 0) {
                failuresLeft[url] = remaining - 1
                true
            } else {
                false
            }
        }
        val expected = (endInclusive - start + 1).toInt()
        val length = if (shouldFail) (expected - 1).coerceAtLeast(0) else expected
        val bytes = ByteArray(length) { offset -> byteAt(start + offset) }
        onProgress(length.toLong())
        return RangePayload(
            bytes = bytes,
            statusCode = 206,
            contentRange = "bytes $start-$endInclusive/$total",
            etag = etag,
            lastModified = null
        )
    }

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = throw UnsupportedOperationException("测试源不提供顺序下载")
}

fun testIntent(
    total: Long,
    etag: String?,
    rangeSupported: Boolean = true,
    id: String = "i1"
) = IntentEntity(
    id = id,
    contentId = "content",
    sourceUrl = "https://example.com/file",
    totalSize = total,
    mimeType = "application/octet-stream",
    etag = etag,
    lastModified = null,
    rangeSupported = rangeSupported,
    valueMapJson = null,
    availabilityContractJson = null,
    state = IntentState.PENDING.name,
    createdAt = 0,
    updatedAt = 0
)

suspend fun seedTestChunks(
    chunkDao: ChunkDao,
    total: Long,
    chunkSize: Long,
    intentId: String = "i1"
) {
    chunkDao.upsertAll(
        DownloadPlan.create(total, chunkSize).chunks.map { chunk ->
            ChunkEntity(
                id = "$intentId-${chunk.index}",
                intentId = intentId,
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
