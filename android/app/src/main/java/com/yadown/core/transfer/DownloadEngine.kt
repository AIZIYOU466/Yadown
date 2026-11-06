package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.io.FileStore
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.ValueMap
import com.yadown.core.survival.FsyncGate
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

class DownloadException(val failureCause: FailureCause, val detail: String) : Exception(detail)

/**
 * 队列驱动的下载引擎，落点：
 * - T1.4 动态裂解：失败可裂解的 chunk 原子替换为两个子 chunk 重试；
 * - T1.5 健康感知并发：按 [HealthTracker] 与 [DispatcherPolicy] 动态调整在途 chunk 数；
 * - T1.6 stall watchdog：无字节进展超时（默认 20s）中断该 chunk 并归因 NETWORK_TIMEOUT。
 */
class DownloadEngine(
    private val source: RangeSource,
    private val files: FileStore,
    private val bitmaps: BitmapStore,
    private val intentDao: IntentDao,
    private val chunkDao: ChunkDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fsyncGate: FsyncGate = FsyncGate()
) {
    private sealed interface Outcome {
        data class Done(val chunk: ChunkRange) : Outcome
        data class Failed(
            val chunk: ChunkRange,
            val cause: FailureCause,
            val detail: String,
            val retryCount: Int
        ) : Outcome
    }

    /**
     * 下载一个 intent。pending 从 DB 加载（含历史裂解子 chunk），
     * 位图标记完成的原始 chunk 跳过；取消协程即暂停（断点由 DB + 位图保证）。
     */
    suspend fun download(
        intentId: String,
        url: String,
        destPath: String,
        concurrency: Int = 4,
        chunkSize: Long = DownloadPlan.DEFAULT_CHUNK_SIZE,
        storageIops: Int = DEFAULT_STORAGE_IOPS,
        valueMap: ValueMap? = null,
        availability: AvailabilityContract? = null,
        onProgress: (DownloadProgress) -> Unit = {},
        onAvailable: () -> Unit = {},
        onChunkDone: (ChunkRange) -> Unit = {}
    ) = withContext(ioDispatcher) {
        require(concurrency > 0) { "concurrency 必须为正: $concurrency" }
        val intent = intentDao.getById(intentId) ?: error("intent 不存在: $intentId")
        val total = intent.totalSize ?: error("intent 缺少 totalSize: $intentId")
        val plan = DownloadPlan.create(total, chunkSize)
        val bitmap = bitmaps.load(intentId, plan.chunks.size)
        val session = RangeSession(intent.etag, intent.lastModified, total)

        val dbChunks = chunkDao.getByIntent(intentId)
        val pending = buildPending(plan, bitmap, dbChunks, valueMap)
        val completed = AtomicLong(dbChunks.filter { it.state == ChunkState.DONE.name }
            .sumOf { it.endByte - it.startByte + 1 })

        onProgress(DownloadProgress(completed.get(), total))

        // 关键区域可能在上一次会话中已落盘，先判定，避免漏报或重复提示。
        val readyRanges = availability?.let { collectReadyRanges(plan, bitmap, dbChunks) }
        val availableUnlocked = AtomicBoolean(
            readyRanges != null && availability != null &&
                availability.isAvailable { s, e -> readyRanges.covers(s, e) }
        )
        if (availableUnlocked.get()) onAvailable()

        val bitmapMutex = Mutex()
        val health = HealthTracker()
        var nextIndex = plan.chunks.size
        var failedOnce = false
        var firstFailure: DownloadException? = null

        coroutineScope {
            val results = Channel<Outcome>(Channel.UNLIMITED)
            var active = 0

            while (pending.isNotEmpty() || active > 0) {
                val limit = DispatcherPolicy.maxRequests(health.score(), storageIops)
                    .coerceIn(1, concurrency)
                while (pending.isNotEmpty() && active < limit) {
                    val (chunk, retry) = pending.removeFirst()
                    active++
                    launch {
                        val outcome = try {
                            downloadChunk(
                                intentId = intentId,
                                url = url,
                                destPath = destPath,
                                chunk = chunk,
                                session = session,
                                bitmap = bitmap,
                                bitmapMutex = bitmapMutex,
                                completed = completed,
                                total = total,
                                onProgress = onProgress,
                                onChunkDone = onChunkDone
                            )
                            health.recordSuccess()
                            if (availability != null && readyRanges != null && !availableUnlocked.get()) {
                                readyRanges.add(chunk.start..chunk.endInclusive)
                                if (availability.isAvailable { s, e -> readyRanges.covers(s, e) } &&
                                    availableUnlocked.compareAndSet(false, true)
                                ) {
                                    onAvailable()
                                }
                            }
                            Outcome.Done(chunk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            health.recordFailure()
                            val cause = (e as? DownloadException)?.failureCause ?: FailureCause.UNKNOWN
                            Outcome.Failed(chunk, cause, e.message ?: "", retry)
                        }
                        results.send(outcome)
                    }
                }

                if (active == 0) break

                when (val outcome = results.receive()) {
                    is Outcome.Done -> active--
                    is Outcome.Failed -> {
                        active--
                        val handled = handleFailed(
                            intentId = intentId,
                            chunk = outcome.chunk,
                            cause = outcome.cause,
                            retryCount = outcome.retryCount,
                            nextIndex = { nextIndex++ },
                            pending = pending
                        )
                        if (!handled) {
                            failedOnce = true
                            if (firstFailure == null) {
                                firstFailure = DownloadException(outcome.cause, outcome.detail)
                            }
                        }
                    }
                }
            }
        }

        if (failedOnce) {
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw firstFailure ?: DownloadException(FailureCause.UNKNOWN, "下载失败")
        }
        intentDao.updateState(intentId, IntentState.COMPLETED.name, clock())
    }

    private fun buildPending(
        plan: DownloadPlan,
        bitmap: ChunkBitmap,
        dbChunks: List<ChunkEntity>,
        valueMap: ValueMap? = null
    ): ArrayDeque<Pair<ChunkRange, Int>> {
        val byIndex = dbChunks.associateBy { parseChunkIndex(it.id) }
        val pending = ArrayDeque<Pair<ChunkRange, Int>>()

        // 位图按 index 索引，因此只在计划内 chunk 上做语义重排；
        // 顺序传输（valueMap == null）时 priorityOrder 返回原序，行为不变。
        for (index in plan.priorityOrder(valueMap)) {
            val entity = byIndex[index] ?: continue
            if (entity.state == ChunkState.DONE.name || entity.state == ChunkState.INVALIDATED.name) continue
            if (bitmap.isDone(index)) continue
            pending.addLast(ChunkRange(index, entity.startByte, entity.endByte) to entity.retryCount)
        }

        // 裂解产生的子 chunk index 超出原始计划，位图不记录，按完成状态过滤后追加。
        dbChunks
            .filter { parseChunkIndex(it.id) >= plan.chunks.size }
            .filter { it.state != ChunkState.DONE.name && it.state != ChunkState.INVALIDATED.name }
            .sortedBy { it.startByte }
            .forEach {
                pending.addLast(
                    ChunkRange(parseChunkIndex(it.id), it.startByte, it.endByte) to it.retryCount
                )
            }

        return pending
    }

    /** 汇总已完成字节区间（位图 + DB），用于判定可用性是否已达成。 */
    private fun collectReadyRanges(
        plan: DownloadPlan,
        bitmap: ChunkBitmap,
        dbChunks: List<ChunkEntity>
    ): RangeCoverage {
        val ranges = RangeCoverage()
        plan.chunks.forEach { chunk ->
            if (bitmap.isDone(chunk.index)) ranges.add(chunk.start..chunk.endInclusive)
        }
        dbChunks.filter { it.state == ChunkState.DONE.name }.forEach { entity ->
            ranges.add(entity.startByte..entity.endByte)
        }
        return ranges
    }

    /** 返回 true 表示已把 chunk 重新排入 pending（裂解或原地重试）；false 表示重试预算耗尽。 */
    private suspend fun handleFailed(
        intentId: String,
        chunk: ChunkRange,
        cause: FailureCause,
        retryCount: Int,
        nextIndex: () -> Int,
        pending: ArrayDeque<Pair<ChunkRange, Int>>
    ): Boolean {
        val id = chunkId(intentId, chunk.index)
        if (retryCount >= ChunkStateMachine.MAX_RETRIES) {
            chunkDao.updateState(id, ChunkState.INVALIDATED.name)
            return false
        }

        // 内容已变（ETag 漂移）直接整体作废，不再裂解重试。
        if (cause == FailureCause.CONTENT_CHANGED) {
            chunkDao.updateState(id, ChunkState.INVALIDATED.name)
            return false
        }

        val split = ChunkSplitter.split(chunk.start, chunk.endInclusive)
        if (split != null) {
            val indexA = nextIndex()
            val indexB = nextIndex()
            val childA = ChunkEntity(
                id = chunkId(intentId, indexA),
                intentId = intentId,
                startByte = split.first.first,
                endByte = split.first.last,
                state = ChunkState.PENDING.name,
                retryCount = 0,
                checksum = null,
                connectionFingerprint = null,
                failureChainJson = null,
                startedAt = null,
                completedAt = null
            )
            val childB = childA.copy(
                id = chunkId(intentId, indexB),
                startByte = split.second.first,
                endByte = split.second.last
            )
            chunkDao.replaceWithChildren(id, childA, childB)
            pending.addLast(
                ChunkRange(indexA, split.first.first, split.first.last) to retryCount
            )
            pending.addLast(
                ChunkRange(indexB, split.second.first, split.second.last) to retryCount
            )
        } else {
            chunkDao.updateState(id, ChunkState.PENDING.name)
            pending.addLast(chunk to retryCount + 1)
        }
        return true
    }

    private suspend fun downloadChunk(
        intentId: String,
        url: String,
        destPath: String,
        chunk: ChunkRange,
        session: RangeSession,
        bitmap: ChunkBitmap,
        bitmapMutex: Mutex,
        completed: AtomicLong,
        total: Long,
        onProgress: (DownloadProgress) -> Unit,
        onChunkDone: (ChunkRange) -> Unit
    ) {
        val id = chunkId(intentId, chunk.index)
        val startedAt = clock()
        chunkDao.updateProgress(id, ChunkState.RUNNING.name, 0, startedAt, null, null, null)

        val payload = readRangeWithWatchdog(url, chunk, startedAt)

        val validation = RangeValidator.validate(
            RangeResponse(
                statusCode = payload.statusCode,
                contentRange = payload.contentRange,
                etag = payload.etag,
                lastModified = payload.lastModified,
                bodyLength = payload.bytes.size.toLong()
            ),
            RangeRequest(chunk.start, chunk.endInclusive),
            session
        )

        when (validation) {
            is ValidationResult.Invalid -> {
                chunkDao.updateProgress(
                    id, ChunkState.FAILED.name, 0, startedAt, null, null, validation.cause.name
                )
                throw DownloadException(validation.cause, validation.detail)
            }

            ValidationResult.Valid -> {
                files.withFile(destPath) { raf ->
                    raf.seek(chunk.start)
                    raf.write(payload.bytes)
                    val nowMs = clock()
                    if (fsyncGate.shouldFsync(nowMs)) {
                        raf.fd.sync()
                        fsyncGate.markFsynced(nowMs)
                    }
                }
                bitmapMutex.withLock {
                    // 裂解产生的子 chunk index 超出原始位图，完成状态由 DB 记录。
                    if (chunk.index < bitmap.totalChunks) {
                        bitmap.markDone(chunk.index)
                        bitmaps.save(intentId, bitmap)
                    }
                }
                chunkDao.updateProgress(id, ChunkState.DONE.name, 0, startedAt, clock(), null, null)
                onProgress(DownloadProgress(completed.addAndGet(chunk.length()), total))
                onChunkDone(chunk)
            }
        }
    }

    /**
     * T1.6：读取期间由 StallWatchdog 监控无进展超时（默认 20s），
     * 超时则取消底层请求并归因 NETWORK_TIMEOUT。
     */
    private suspend fun readRangeWithWatchdog(
        url: String,
        chunk: ChunkRange,
        startedAt: Long
    ): RangePayload {
        val watchdog = StallWatchdog(
            timeoutMs = StallWatchdog.DEFAULT_TIMEOUT_MS,
            startAtMs = startedAt
        )
        val stalled = AtomicBoolean(false)
        return coroutineScope {
            val work = async(ioDispatcher) {
                source.readRange(url, chunk.start, chunk.endInclusive) { _ ->
                    watchdog.onProgress(clock())
                }
            }
            while (work.isActive) {
                if (watchdog.isStalled(clock())) {
                    stalled.set(true)
                    work.cancel()
                    break
                }
                delay(WATCHDOG_POLL_MS)
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (stalled.get()) {
                    throw DownloadException(FailureCause.NETWORK_TIMEOUT, "无进展超过 ${StallWatchdog.DEFAULT_TIMEOUT_MS}ms")
                }
                throw e
            } catch (e: Exception) {
                if (stalled.get()) {
                    throw DownloadException(FailureCause.NETWORK_TIMEOUT, "无进展超过 ${StallWatchdog.DEFAULT_TIMEOUT_MS}ms")
                }
                throw e
            }
        }
    }

    private fun chunkId(intentId: String, index: Int): String = "$intentId-$index"

    private fun parseChunkIndex(id: String): Int =
        id.substringAfterLast('-').toIntOrNull() ?: -1

    companion object {
        private const val WATCHDOG_POLL_MS = 500L
        private const val DEFAULT_STORAGE_IOPS = 3_000
    }
}