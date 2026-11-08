package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.io.FileStore
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.ValueMap
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceHealthRegistry
import com.yadown.core.source.SourceRoute
import com.yadown.core.source.SourceSelection
import com.yadown.core.telemetry.SessionTelemetry
import com.yadown.core.telemetry.TransferTelemetry
import com.yadown.core.survival.FsyncGate
import java.io.File
import java.io.RandomAccessFile
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.coroutines.withTimeoutOrNull

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

class DownloadException(
    val failureCause: FailureCause,
    val detail: String,
    /** 服务端 429 等给出的重试等待，用于熔断冷却而不是盲目重试。 */
    val retryAfterMs: Long? = null
) : Exception(detail)

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
    private val fsyncGate: FsyncGate = FsyncGate(),
    private val healthRegistry: SourceHealthRegistry? = null,
    private val monitor: ChunkThroughputMonitor = ChunkThroughputMonitor(clock),
    private val breaker: SourceCircuitBreaker = SourceCircuitBreaker(clock),
    private val pipeline: WritePipeline = WritePipeline()
) {
    private data class PendingChunk(
        val chunk: ChunkRange,
        val retryCount: Int,
        val sourceSlot: Int
    )
    private sealed interface Outcome {
        data class Done(val chunk: ChunkRange) : Outcome
        data class Slow(
            val chunk: ChunkRange,
            val rate: Double,
            val retryCount: Int,
            val sourceSlot: Int
        ) : Outcome

        data class Failed(
            val chunk: ChunkRange,
            val cause: FailureCause,
            val detail: String,
            val retryCount: Int,
            val sourceSlot: Int
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
        linkCount: Int = 1,
        valueMap: ValueMap? = null,
        availability: AvailabilityContract? = null,
        onProgress: (DownloadProgress) -> Unit = {},
        onAvailable: () -> Unit = {},
        onChunkDone: (ChunkRange) -> Unit = {},
        routes: List<SourceRoute> = emptyList()
    ) = withContext(ioDispatcher) {
        require(concurrency > 0) { "concurrency 必须为正: $concurrency" }
        val intent = intentDao.getById(intentId) ?: error("intent 不存在: $intentId")
        val total = intent.totalSize ?: error("intent 缺少 totalSize: $intentId")
        val sessionStartedAt = clock()
        if (!intent.rangeSupported) {
            downloadSequential(intentId, url, destPath, total, onProgress)
            return@withContext
        }
        val sourceRoutes = if (routes.isEmpty()) listOf(defaultRoute(url)) else routes
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
        val coordinator = CommitCoordinator(
            intentId = intentId,
            chunkDao = chunkDao,
            bitmaps = bitmaps,
            bitmap = bitmap,
            fingerprint = fingerprint,
            pipeline = pipeline,
            fsyncGate = fsyncGate,
            clock = clock
        )
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
        // 每源在途数用学习到的连接上限约束；与全局 DispatcherPolicy 共同构成两层闸门。
        val inFlight = ConcurrentHashMap<String, AtomicInteger>()
            .apply { sourceRoutes.forEach { getOrPut(it.healthKey) { AtomicInteger() } } }
        fun inFlightOf(key: String): Int = inFlight[key]?.get() ?: 0
        // 子块 index 必须从持久化的最大值继续；从 plan.chunks.size 重开会覆盖上次会话的子块。
        var nextIndex = ((dbChunks.maxOfOrNull { parseChunkIndex(it.id) } ?: -1) + 1)
            .coerceAtLeast(plan.chunks.size)
        var failedOnce = false
        var firstFailure: DownloadException? = null
        // 只在真正发出请求（取得传输许可）时计入并发，排队等许可的块不算在飞。
        val requestsInFlight = AtomicInteger()

        coroutineScope {
            val results = Channel<Outcome>(Channel.UNLIMITED)
            var active = 0
            // 慢裂解产生的新块允许临时超出并发上限 20%，让快连接多吃；块完成后回落。
            var splitBonus = 0
            val maxSplitBonus = maxOf(1, concurrency / 5)

            while (pending.isNotEmpty() || active > 0) {
                val limit = (DispatcherPolicy.maxRequests(health.score(), storageIops, linkCount) + splitBonus)
                    .coerceAtMost(concurrency + maxSplitBonus)
                    .coerceAtLeast(1)
                while (pending.isNotEmpty() && active < limit) {
                    val next = pending.removeFirst()
                    val route = SourceSelection.pick(
                        routes = sourceRoutes,
                        slot = next.chunk.index + next.sourceSlot,
                        inFlight = ::inFlightOf,
                        available = { candidate -> !breaker.isOpen(candidate.healthKey, clock()) }
                    )
                    inFlight.getValue(route.healthKey).incrementAndGet()
                    active++
                    launch {
                        val outcome = try {
                            val startedAt = clock()
                            downloadChunk(
                                intentId = intentId,
                                route = route,
                                destPath = destPath,
                                chunk = next.chunk,
                                session = session,
                                bitmap = bitmap,
                                bitmapMutex = bitmapMutex,
                                completed = completed,
                                total = total,
                                coordinator = coordinator,
                                perKeyLimit = concurrency,
                                requestsInFlight = requestsInFlight,
                                onRequestStarted = { concurrent ->
                                    SessionTelemetry.onPeakConcurrency(intentId, concurrent)
                                    SessionTelemetry.onFirstRequest(intentId, clock() - sessionStartedAt)
                                },
                                onFirstByte = {
                                    SessionTelemetry.onFirstByte(intentId, clock() - sessionStartedAt)
                                },
                                onProgress = onProgress,
                                onChunkDone = onChunkDone
                            )
                            val elapsedMs = (clock() - startedAt).coerceAtLeast(1)
                            val throughput = next.chunk.length() * 1000.0 / elapsedMs
                            runCatching {
                                healthRegistry?.recordSuccess(route.healthKey, throughput, elapsedMs.toDouble())
                            }
                            health.recordSuccess()
                            breaker.onSuccess(route.healthKey)
                            SessionTelemetry.onChunkDone(intentId, next.chunk.length())
                            SessionTelemetry.onFirstChunk(intentId, clock() - sessionStartedAt)
                            if (availability != null && readyRanges != null && !availableUnlocked.get()) {
                                readyRanges.add(next.chunk.start..next.chunk.endInclusive)
                                if (availability.isAvailable { s, e -> readyRanges.covers(s, e) } &&
                                    availableUnlocked.compareAndSet(false, true)
                                ) {
                                    TransferTelemetry.onAvailable(clock() - sessionStartedAt)
                                    onAvailable()
                                }
                            }
                            Outcome.Done(next.chunk)
                        } catch (e: SlowChunkSignal) {
                            Outcome.Slow(next.chunk, e.rate, next.retryCount, next.sourceSlot)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            health.recordFailure()
                            val cause = (e as? DownloadException)?.failureCause ?: FailureCause.UNKNOWN
                            breaker.onFailure(
                                route.healthKey,
                                cause,
                                (e as? DownloadException)?.retryAfterMs
                            )
                            runCatching { healthRegistry?.recordFailure(route.healthKey, cause) }
                            TransferTelemetry.onChunkFailed(cause)
                            SessionTelemetry.onChunkFailed(intentId)
                            Outcome.Failed(next.chunk, cause, e.message ?: "", next.retryCount, next.sourceSlot)
                        } finally {
                            inFlight.getValue(route.healthKey).decrementAndGet()
                        }
                        results.send(outcome)
                    }
                }

                if (active == 0) break

                when (val outcome = results.receive()) {
                    is Outcome.Done -> {
                        active--
                        monitor.onChunkFinished(outcome.chunk.index)
                        splitBonus = (splitBonus - 1).coerceAtLeast(0)
                    }
                    is Outcome.Slow -> {
                        active--
                        monitor.onChunkFinished(outcome.chunk.index)
                        when (
                            handleSlowSplit(
                                intentId = intentId,
                                chunk = outcome.chunk,
                                rate = outcome.rate,
                                retryCount = outcome.retryCount,
                                sourceSlot = outcome.sourceSlot,
                                nextIndex = { nextIndex++ },
                                pending = pending
                            )
                        ) {
                            SlowHandling.Split -> {
                                splitBonus = (splitBonus + 1).coerceAtMost(maxSplitBonus)
                            }
                            SlowHandling.Retry -> Unit
                            SlowHandling.Exhausted -> {
                                failedOnce = true
                                if (firstFailure == null) {
                                    firstFailure = DownloadException(
                                        FailureCause.NETWORK_TIMEOUT,
                                        "慢块重试预算耗尽（速率 ${outcome.rate.toLong()} B/s）"
                                    )
                                }
                            }
                        }
                    }
                    is Outcome.Failed -> {
                        active--
                        monitor.onChunkFinished(outcome.chunk.index)
                        val handled = handleFailed(
                            intentId = intentId,
                            chunk = outcome.chunk,
                            cause = outcome.cause,
                            retryCount = outcome.retryCount,
                            sourceSlot = outcome.sourceSlot,
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

        SessionTelemetry.onTransferFinished(intentId, clock() - sessionStartedAt)

        if (failedOnce) {
            if (firstFailure?.failureCause == FailureCause.SERVER_RANGE_UNSUPPORTED) {
                // 下次用户重试时走顺序下载，而不是继续用 Range 撞墙。
                intentDao.upsert(intent.copy(rangeSupported = false, updatedAt = clock()))
            }
            intentDao.updateState(intentId, IntentState.FAILED.name, clock())
            throw firstFailure ?: DownloadException(FailureCause.UNKNOWN, "下载失败")
        }

        val flushStartedAt = clock()
        try {
            pipeline.flush()
            coordinator.commit(force = true)
        } catch (e: Exception) {
            throw DownloadException(
                FailureCause.STORAGE_UNREACHABLE,
                "写盘/提交失败: ${e.message}"
            )
        }
        SessionTelemetry.onFlush(intentId, clock() - flushStartedAt)

        val finalChunks = chunkDao.getByIntent(intentId)
        val fileLength = File(destPath).length()
        val verifyStartedAt = clock()
        val report = CompletionVerifier.verify(total, finalChunks, fileLength)
        SessionTelemetry.onVerify(intentId, clock() - verifyStartedAt)
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
    ): ArrayDeque<PendingChunk> {
        val byIndex = dbChunks.associateBy { parseChunkIndex(it.id) }
        val pending = ArrayDeque<PendingChunk>()

        // 位图按 index 索引，因此只在计划内 chunk 上做语义重排；
        // 顺序传输（valueMap == null）时 priorityOrder 返回原序，行为不变。
        for (index in plan.priorityOrder(valueMap)) {
            val entity = byIndex[index] ?: continue
            if (entity.state == ChunkState.DONE.name || entity.state == ChunkState.INVALIDATED.name) continue
            // 位图只是索引，不是提交记录：DB 未 DONE 时必须重新下载。
            pending.addLast(
                PendingChunk(
                    ChunkRange(index, entity.startByte, entity.endByte),
                    entity.retryCount,
                    0
                )
            )
        }

        // 裂解产生的子 chunk index 超出原始计划，位图不记录，按完成状态过滤后追加。
        dbChunks
            .filter { parseChunkIndex(it.id) >= plan.chunks.size }
            .filter { it.state != ChunkState.DONE.name && it.state != ChunkState.INVALIDATED.name }
            .sortedBy { it.startByte }
            .forEach {
                pending.addLast(
                    PendingChunk(
                        ChunkRange(parseChunkIndex(it.id), it.startByte, it.endByte),
                        it.retryCount,
                        0
                    )
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
        sourceSlot: Int,
        nextIndex: () -> Int,
        pending: ArrayDeque<PendingChunk>
    ): Boolean {
        val id = chunkId(intentId, chunk.index)
        if (retryCount >= ChunkStateMachine.MAX_RETRIES) {
            chunkDao.updateState(id, ChunkState.INVALIDATED.name)
            return false
        }

        // 429 不裂解：裂解只会放大无效请求；按服务端节奏冷却，由断路器换源。
        if (cause == FailureCause.RATE_LIMITED) {
            chunkDao.updateState(id, ChunkState.PENDING.name)
            pending.addLast(PendingChunk(chunk, retryCount + 1, sourceSlot + 1))
            SessionTelemetry.onRetry(intentId)
            return true
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
            val nextSlot = sourceSlot + 1
            pending.addLast(
                PendingChunk(ChunkRange(indexA, split.first.first, split.first.last), retryCount + 1, nextSlot)
            )
            pending.addLast(
                PendingChunk(ChunkRange(indexB, split.second.first, split.second.last), retryCount + 1, nextSlot)
            )
            SessionTelemetry.onRetry(intentId)
        } else {
            chunkDao.updateState(id, ChunkState.PENDING.name)
            pending.addLast(PendingChunk(chunk, retryCount + 1, sourceSlot + 1))
            SessionTelemetry.onRetry(intentId)
        }
        return true
    }

    private suspend fun downloadChunk(
        intentId: String,
        route: SourceRoute,
        destPath: String,
        chunk: ChunkRange,
        session: RangeSession,
        bitmap: ChunkBitmap,
        bitmapMutex: Mutex,
        completed: AtomicLong,
        total: Long,
        coordinator: CommitCoordinator,
        perKeyLimit: Int,
        requestsInFlight: AtomicInteger,
        onRequestStarted: (Int) -> Unit,
        onFirstByte: () -> Unit,
        onProgress: (DownloadProgress) -> Unit,
        onChunkDone: (ChunkRange) -> Unit
    ) {
        val id = chunkId(intentId, chunk.index)
        val startedAt = clock()
        chunkDao.updateProgress(id, ChunkState.RUNNING.name, 0, startedAt, null, null, null)

        // per-key 闸门取会话预算，与全局闸门同源：
        // 两套独立上限（源健康快照 vs 拓扑预算）取 min 会让预算形同虚设。
        val payload = TransferLimiter.withPermit(route.url, route.linkId, perKeyLimit) {
            onRequestStarted(requestsInFlight.incrementAndGet())
            try {
                val lastEmitAt = java.util.concurrent.atomic.AtomicLong(0)
                readRangeWithWatchdog(
                    // 裂解与 stall 放宽看的是「该源能并行几条」（源维度信号），不是并发闸门。
                    intentId, route.url, route.linkId, route.connectionLimit, chunk, startedAt, total,
                    onBytesProgress = { bytes ->
                        if (bytes > 0) onFirstByte()
                        // 块内字节进度上报：进度条不再只在块完成时跳一次，慢块期间也能看到推进。
                        val now = clock()
                        if (now - lastEmitAt.get() >= PROGRESS_EMIT_MS) {
                            lastEmitAt.set(now)
                            onProgress(DownloadProgress(completed.get() + bytes, total))
                        }
                    }
                )
            } finally {
                requestsInFlight.decrementAndGet()
            }
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
                throw DownloadException(validation.cause, validation.detail, payload.retryAfterMs)
            }

            ValidationResult.Valid -> {
                val checksum = XxHash64.hash(payload.bytes)
                // 传输协程只投递写任务；数据落盘与 DB DONE 由协调器按周期成批完成。
                pipeline.submit(WritePipeline.WriteTask(destPath, chunk.start, payload.bytes))
                bitmapMutex.withLock {
                    // 裂解产生的子 chunk index 超出原始位图，完成状态由 DB 记录。
                    if (chunk.index < bitmap.totalChunks) {
                        bitmap.markDone(chunk.index)
                    }
                }
                coordinator.enqueue(
                    CommitCoordinator.DoneRecord(
                        id = id,
                        startedAt = startedAt,
                        completedAt = clock(),
                        checksum = checksum
                    )
                )
                coordinator.commit(force = false)
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
        intentId: String,
        url: String,
        linkId: String?,
        connectionLimit: Int,
        chunk: ChunkRange,
        startedAt: Long,
        totalSize: Long,
        onBytesProgress: (Long) -> Unit = {}
    ): RangePayload {
        // 低并发/慢源放宽 stall：慢边缘节点上 20s 就取消会把接近完成的请求反复掐断。
        val stallTimeoutMs = if (connectionLimit <= 2) {
            SLOW_SOURCE_STALL_TIMEOUT_MS
        } else {
            StallWatchdog.DEFAULT_TIMEOUT_MS
        }
        val watchdog = StallWatchdog(
            timeoutMs = stallTimeoutMs,
            startAtMs = startedAt
        )
        val stalled = AtomicBoolean(false)
        val splitRequested = AtomicBoolean(false)
        val received = AtomicLong(0)
        return coroutineScope {
            val work = async(ioDispatcher) {
                source.readRangeChecked(
                    url = url,
                    start = chunk.start,
                    endInclusive = chunk.endInclusive,
                    totalSize = totalSize,
                    onProgress = { bytes ->
                        received.set(bytes)
                        watchdog.onProgress(clock())
                        monitor.onProgress(chunk.index, bytes)
                        onBytesProgress(bytes)
                    },
                    linkId = linkId
                )
            }
            while (true) {
                // 块在轮询周期内完成时立即返回，不再等满 500ms（旧实现每块固定最多 +500ms）。
                val payload = withTimeoutOrNull(WATCHDOG_POLL_MS) { work.await() }
                if (payload != null) return@coroutineScope payload
                if (watchdog.isStalled(clock())) {
                    stalled.set(true)
                    work.cancel()
                    break
                }
                val transferred = received.get()
                if (monitor.shouldSplit(
                        chunkIndex = chunk.index,
                        transferredBytes = transferred,
                        chunkLength = chunk.length(),
                        connectionLimit = connectionLimit
                    )
                ) {
                    splitRequested.set(true)
                    work.cancel()
                    break
                }
            }
            try {
                work.await()
            } catch (e: CancellationException) {
                if (splitRequested.get()) {
                    throw SlowChunkSignal(monitor.rateOf(chunk.index))
                }
                if (stalled.get()) {
                    TransferTelemetry.onStall()
                    SessionTelemetry.onStall(intentId)
                    throw DownloadException(FailureCause.NETWORK_TIMEOUT, "无进展超过 ${stallTimeoutMs}ms")
                }
                throw e
            } catch (e: Exception) {
                if (stalled.get()) {
                    TransferTelemetry.onStall()
                    SessionTelemetry.onStall(intentId)
                    throw DownloadException(FailureCause.NETWORK_TIMEOUT, "无进展超过 ${stallTimeoutMs}ms")
                }
                throw e
            }
        }
    }

    /**
     * 慢块处理：按实时速率自适应拆分为两个子块，插到队列最前，让空闲 worker 立即接管；
     * 已达最小粒度时换源重试，并消耗失败重试预算，避免无限拆分。
     */
    private suspend fun handleSlowSplit(
        intentId: String,
        chunk: ChunkRange,
        rate: Double,
        retryCount: Int,
        sourceSlot: Int,
        nextIndex: () -> Int,
        pending: ArrayDeque<PendingChunk>
    ): SlowHandling {
        val id = chunkId(intentId, chunk.index)
        val split = ChunkSplitter.splitAdaptive(chunk.start, chunk.endInclusive, rate)
        if (split == null) {
            if (retryCount >= ChunkStateMachine.MAX_RETRIES) {
                chunkDao.updateState(id, ChunkState.INVALIDATED.name)
                return SlowHandling.Exhausted
            }
            chunkDao.updateState(id, ChunkState.PENDING.name)
            pending.addFirst(PendingChunk(chunk, retryCount + 1, sourceSlot + 1))
            SessionTelemetry.onRetry(intentId)
            return SlowHandling.Retry
        }

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
        pending.addFirst(
            PendingChunk(ChunkRange(indexA, split.first.first, split.first.last), 0, sourceSlot)
        )
        pending.addFirst(
            PendingChunk(ChunkRange(indexB, split.second.first, split.second.last), 0, sourceSlot)
        )
        TransferTelemetry.onSlowSplit()
        SessionTelemetry.onSlowSplit(intentId)
        return SlowHandling.Split
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

    private sealed interface SlowHandling {
        data object Split : SlowHandling
        data object Retry : SlowHandling
        data object Exhausted : SlowHandling
    }

    private class SlowChunkSignal(val rate: Double) : Exception("慢块需要裂解")

    private fun parseChunkIndex(id: String): Int =
        id.substringAfterLast('-').toIntOrNull() ?: -1

    /** 单源调用方的默认路由；连接上限不做限制，保持接入多源前的原行为。 */
    private fun defaultRoute(url: String): SourceRoute = SourceRoute(
        endpoint = SourceEndpoint(url),
        score = 1.0,
        reliability = 1.0,
        throughputScore = 1.0,
        connectionLimit = Int.MAX_VALUE,
        usable = true
    )

    companion object {
        private const val WATCHDOG_POLL_MS = 500L
        private const val PROGRESS_EMIT_MS = 200L
        private const val SLOW_SOURCE_STALL_TIMEOUT_MS = 45_000L
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