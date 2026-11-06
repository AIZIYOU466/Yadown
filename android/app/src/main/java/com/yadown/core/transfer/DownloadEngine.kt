package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.io.FileStore
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.ValueMap
import com.yadown.core.telemetry.TransferTelemetry
import com.yadown.core.survival.FsyncGate
import java.io.File
import java.io.RandomAccessFile
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
        val sessionStartedAt = clock()
        if (!intent.rangeSupported) {
            downloadSequential(intentId, url, destPath, total, onProgress)
            return@withContext
        }
        val plan = DownloadPlan.create(total, chunkSize)
        val dbChunks = chunkDao.getByIntent(intentId)
        val fingerprint = PlanFingerprint.of(total, chunkSize)
        // 位图损坏/指纹不符时不信任空位图，按 DB 的 DONE 重建，避免把洞当成已完成。
        val bitmap = bitmaps.loadChecked(intentId, plan.chunks.size, fingerprint)
            ?: ChunkBitmap(plan.chunks.size).also { rebuilt ->
                dbChunks.asSequence()
                    .filter { it.state == ChunkState.DONE.name }
                    .map { parseChunkIndex(it.id) }
                    .filter { it in 0 until plan.chunks.size }
                    .forEach { rebuilt.markDone(it) }
            }
        reconcileBitmap(plan, dbChunks, bitmap)
        // 位图是索引而非提交记录，写失败不能阻断下载；DB 仍是 DONE 的权威。
        runCatching { bitmaps.save(intentId, bitmap, fingerprint) }
        val session = RangeSession(intent.etag, intent.lastModified, total)
        val pending = buildPending(plan, dbChunks, valueMap)
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
        // 子块 index 必须从持久化的最大值继续；从 plan.chunks.size 重开会覆盖上次会话的子块。
        var nextIndex = ((dbChunks.maxOfOrNull { parseChunkIndex(it.id) } ?: -1) + 1)
            .coerceAtLeast(plan.chunks.size)
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
                                fingerprint = fingerprint,
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
                                    TransferTelemetry.onAvailable(clock() - sessionStartedAt)
                                    onAvailable()
                                }
                            }
                            Outcome.Done(chunk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            health.recordFailure()
                            val cause = (e as? DownloadException)?.failureCause ?: FailureCause.UNKNOWN
                            TransferTelemetry.onChunkFailed(cause)
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
            if (firstFailure?.failureCause == FailureCause.SERVER_RANGE_UNSUPPORTED) {
                // 下次用户重试时走顺序下载，而不是继续用 Range 撞墙。
                intentDao.upsert(intent.copy(rangeSupported = false, updatedAt = clock()))
            }
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw firstFailure ?: DownloadException(FailureCause.UNKNOWN, "下载失败")
        }

        val finalChunks = chunkDao.getByIntent(intentId)
        val fileLength = File(destPath).length()
        val report = CompletionVerifier.verify(total, finalChunks, fileLength)
        if (!report.complete) {
            markIncompleteForRepair(intentId, total, fileLength, report)
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw DownloadException(
                FailureCause.INCOMPLETE,
                "完成校验失败：覆盖 ${report.coveredBytes}/${report.totalBytes}，" +
                    "缺失 ${report.missing.size} 个区间，文件长度 $fileLength"
            )
        }
        intentDao.updateState(intentId, IntentState.COMPLETED.name, clock())
    }

    /** 位图必须与 DB 的 DONE 集合同步；位图独有或 DB 独有的位都视为未提交。 */
    private fun reconcileBitmap(
        plan: DownloadPlan,
        dbChunks: List<ChunkEntity>,
        bitmap: ChunkBitmap
    ) {
        val done = dbChunks.asSequence()
            .filter { it.state == ChunkState.DONE.name }
            .map { parseChunkIndex(it.id) }
            .toSet()
        for (chunk in plan.chunks) {
            if (chunk.index in done) {
                bitmap.markDone(chunk.index)
            } else if (bitmap.isDone(chunk.index)) {
                bitmap.markPending(chunk.index)
            }
        }
    }

    private fun buildPending(
        plan: DownloadPlan,
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
            // 位图只是索引，不是提交记录：DB 未 DONE 时必须重新下载。
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

    /**
     * 完成校验失败时，把与缺失区间重叠的 DONE 分块退回 PENDING，并丢弃位图。
     * 这样用户重试时真正重下缺失字节，而不是再次被“队列为空”骗过。
     */
    private suspend fun markIncompleteForRepair(
        intentId: String,
        total: Long,
        fileLength: Long,
        report: CompletionReport
    ) {
        val repairRanges = report.missing.toMutableList()
        if (fileLength < total) {
            repairRanges += fileLength.coerceAtLeast(0)..(total - 1)
        }
        if (repairRanges.isEmpty()) return

        chunkDao.getByIntent(intentId)
            .filter { it.state == ChunkState.DONE.name }
            .filter { chunk ->
                repairRanges.any { range ->
                    range.first <= chunk.endByte && range.last >= chunk.startByte
                }
            }
            .forEach { chunk ->
                chunkDao.updateProgress(
                    chunk.id,
                    ChunkState.PENDING.name,
                    0,
                    null,
                    null,
                    null,
                    null
                )
            }
        bitmaps.delete(intentId)
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

        // 终态原因不裂解：裂解只会放大无效请求，不会让 403/416/ENOSPC 变可重试。
        if (cause == FailureCause.CONTENT_CHANGED || cause in NO_SPLIT_CAUSES) {
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
                retryCount = retryCount + 1,
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
                ChunkRange(indexA, split.first.first, split.first.last) to retryCount + 1
            )
            pending.addLast(
                ChunkRange(indexB, split.second.first, split.second.last) to retryCount + 1
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
        fingerprint: String,
        bitmapMutex: Mutex,
        completed: AtomicLong,
        total: Long,
        onProgress: (DownloadProgress) -> Unit,
        onChunkDone: (ChunkRange) -> Unit
    ) {
        val id = chunkId(intentId, chunk.index)
        val startedAt = clock()
        chunkDao.updateProgress(id, ChunkState.RUNNING.name, 0, startedAt, null, null, null)

        val payload = TransferLimiter.withPermit(url) {
            readRangeWithWatchdog(url, chunk, startedAt, total)
        }

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
                val checksum = XxHash64.hash(payload.bytes)
                files.withFile(destPath) { raf ->
                    raf.seek(chunk.start)
                    raf.write(payload.bytes)
                    // DONE 之前数据必须已持久；先保证正确性，后续再做分组提交优化。
                    raf.fd.sync()
                    fsyncGate.markFsynced(clock())
                }
                bitmapMutex.withLock {
                    // 裂解产生的子 chunk index 超出原始位图，完成状态由 DB 记录。
                    if (chunk.index < bitmap.totalChunks) {
                        bitmap.markDone(chunk.index)
                        bitmaps.save(intentId, bitmap, fingerprint)
                    }
                }
                chunkDao.updateProgress(id, ChunkState.DONE.name, 0, startedAt, clock(), checksum, null)
                TransferTelemetry.onChunkDone(chunk.length())
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
        startedAt: Long,
        totalSize: Long
    ): RangePayload {
        val watchdog = StallWatchdog(
            timeoutMs = StallWatchdog.DEFAULT_TIMEOUT_MS,
            startAtMs = startedAt
        )
        val stalled = AtomicBoolean(false)
        return coroutineScope {
            val work = async(ioDispatcher) {
                source.readRangeChecked(url, chunk.start, chunk.endInclusive, totalSize) { _ ->
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

    /** 非 Range 源的顺序下载：单流落盘，不从内存缓存整个文件；断点续传退化为从头开始。 */
    private suspend fun downloadSequential(
        intentId: String,
        url: String,
        destPath: String,
        total: Long,
        onProgress: (DownloadProgress) -> Unit
    ) {
        val file = File(destPath)
        file.parentFile?.mkdirs()
        onProgress(DownloadProgress(0, total))

        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.setLength(0)
                source.readSequential(
                    url = url,
                    start = 0L,
                    onProgress = { written -> onProgress(DownloadProgress(written, total)) }
                ) { bytes -> raf.write(bytes) }
                raf.fd.sync()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw DownloadException(
                FailureCause.SERVER_SILENT_REJECTION,
                "顺序下载失败: ${e.message}"
            )
        }

        val length = file.length()
        if (length != total) {
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw DownloadException(
                FailureCause.INCOMPLETE,
                "顺序下载完成但长度 $length != $total"
            )
        }
        intentDao.updateState(intentId, IntentState.COMPLETED.name, clock())
    }

    private fun chunkId(intentId: String, index: Int): String = "$intentId-$index"

    private fun parseChunkIndex(id: String): Int =
        id.substringAfterLast('-').toIntOrNull() ?: -1

    companion object {
        private const val WATCHDOG_POLL_MS = 500L
        private const val DEFAULT_STORAGE_IOPS = 3_000
        private val NO_SPLIT_CAUSES = setOf(
            FailureCause.SERVER_RANGE_UNSUPPORTED,
            FailureCause.RANGE_NOT_SATISFIABLE,
            FailureCause.STORAGE_FULL,
            FailureCause.STORAGE_UNREACHABLE,
            FailureCause.TLS_HANDSHAKE_FAILED
        )
    }
}