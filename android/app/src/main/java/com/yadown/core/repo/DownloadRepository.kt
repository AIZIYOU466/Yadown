package com.yadown.core.repo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.dao.TransferEventDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.db.entity.TransferEventEntity
import com.yadown.core.net.RangeSource
import com.yadown.core.net.ProbeResult
import com.yadown.core.io.DownloadPaths
import com.yadown.core.net.LinkRegistry
import com.yadown.core.net.TransportTopologySettings
import com.yadown.core.peer.PeerSourceProvider
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.ContentDescriptor
import com.yadown.core.semantic.FormatResolverRegistry
import com.yadown.core.semantic.ValueMap
import com.yadown.core.source.ContentIdentity
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.source.SourceRouter
import com.yadown.core.telemetry.SessionTelemetry
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.ChunkState
import com.yadown.core.transfer.CompletionVerifier
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.DownloadException
import com.yadown.core.transfer.DownloadPlan
import com.yadown.core.transfer.IntentState
import com.yadown.core.transfer.RangeCoverage
import com.yadown.core.transfer.RangeReadySignal
import com.yadown.core.transfer.TransferLimiter
import com.yadown.core.survival.Carrier
import com.yadown.core.survival.DeferredSyncWorker
import com.yadown.core.survival.LifecycleContext
import com.yadown.core.survival.LifecycleManager
import com.yadown.core.survival.PlatformProbe
import com.yadown.core.survival.StorageTiering
import com.yadown.service.DownloadService
import com.yadown.service.UidtScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class TaskUi(
    val id: String,
    val sourceUrl: String,
    val fileName: String,
    val mimeType: String?,
    val state: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long,
    val capability: String?,
    val availableNow: Boolean,
    val sourceCount: Int
)

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: DownloadEngine,
    private val source: RangeSource,
    private val bitmaps: BitmapStore,
    private val resolvers: FormatResolverRegistry,
    private val intentDao: IntentDao,
    private val chunkDao: ChunkDao,
    private val eventDao: TransferEventDao,
    private val sourceRouter: SourceRouter,
    private val peerSourceProvider: PeerSourceProvider,
    private val linkRegistry: LinkRegistry,
    private val topologySettings: TransportTopologySettings
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speedSamples = ConcurrentHashMap<String, Pair<Long, Long>>()
    private val progressEmitAt = ConcurrentHashMap<String, Long>()
    private val coverages = ConcurrentHashMap<String, RangeCoverage>()
    private val completedIntents = ConcurrentHashMap.newKeySet<String>()
    private val unlockState = ConcurrentHashMap<String, Boolean>()
    private val descriptors = ConcurrentHashMap<String, ContentDescriptor>()
    private val totalSizes = ConcurrentHashMap<String, Long>()
    private val storageTiering = StorageTiering(context)

    fun observeTasks(): Flow<List<TaskUi>> =
        combine(intentDao.observeAll(), progress, speeds) { intents, downloaded, speedMap ->
            intents.map { intent ->
                intent.totalSize?.let { totalSizes[intent.id] = it }
                if (intent.state == IntentState.COMPLETED.name) {
                    completedIntents.add(intent.id)
                }
                TaskUi(
                    id = intent.id,
                    sourceUrl = intent.sourceUrl,
                    fileName = fileNameOf(intent.sourceUrl),
                    mimeType = intent.mimeType,
                    state = intent.state,
                    downloadedBytes = downloaded[intent.id] ?: 0L,
                    totalBytes = intent.totalSize ?: 0L,
                    speedBytesPerSec = speedMap[intent.id] ?: 0L,
                    capability = decodeAvailability(intent.availabilityContractJson)
                        ?.capability?.name,
                    availableNow = intent.state == IntentState.COMPLETED.name ||
                        intent.id in completedIntents ||
                        unlockState[intent.id] == true,
                    sourceCount = 1 + decodeMirrors(intent.mirrorUrlsJson).size
                )
            }
        }

    suspend fun createAndStart(url: String, mirrors: List<String> = emptyList()): String {
        // 低存储时提前失败，避免下载到一半写满分区。
        if (PlatformProbe.probeStorageCapability(context).lowStorage) {
            error("存储空间不足，请清理后重试")
        }
        val probe = source.probe(url)
        val total = probe.totalSize ?: error("无法确定文件大小，服务器未返回 Content-Length/Content-Range")
        val verifiedMirrors = verifyMirrors(probe, url, mirrors)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        // L1：解析内容语义。失败（非 MP4/APK/PDF、结构异常、无 Range）返回 null，退化为顺序传输。
        val descriptor = if (probe.rangeSupported) {
            resolvers.resolve(probe, url) { start, end ->
                source.readRange(url, start, end).bytes
            }
        } else {
            null
        }
        if (descriptor != null) descriptors[id] = descriptor

        intentDao.upsert(
            IntentEntity(
                id = id,
                contentId = url,
                sourceUrl = url,
                totalSize = total,
                mimeType = probe.mimeType,
                etag = probe.etag,
                lastModified = probe.lastModified,
                rangeSupported = probe.rangeSupported,
                valueMapJson = descriptor?.valueMap?.let { json.encodeToString(it) },
                availabilityContractJson = descriptor?.availabilityContract
                    ?.let { json.encodeToString(it) },
                mirrorUrlsJson = verifiedMirrors.takeIf { it.isNotEmpty() }
                    ?.let { json.encodeToString(it) },
                state = IntentState.ACTIVE.name,
                createdAt = now,
                updatedAt = now
            )
        )

        if (probe.rangeSupported) {
            val plan = DownloadPlan.create(total)
            chunkDao.upsertAll(
                plan.chunks.map { chunk ->
                    ChunkEntity(
                        id = chunkId(id, chunk.index),
                        intentId = id,
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

        // 预分配目标文件：播放器的 DataSource 用 file.length() 判断可读上界，
        // 不预分配会导致边下边播在打开瞬间的长度处提前 EOF。
        runCatching {
            val dest = File(destPathOf(id, url))
            dest.parentFile?.mkdirs()
            RandomAccessFile(dest, "rw").use { it.setLength(total) }
        }

        start(id, url)
        return id
    }

    fun start(intentId: String, url: String) {
        if (jobs[intentId]?.isActive == true) return
        // 手动启动优先于后台兜底，避免同一任务被 WorkManager/UIDT 与前台服务同时拉取。
        DeferredSyncWorker.cancel(context, intentId)
        UidtScheduler.cancel(context, intentId)
        // Doze 下不抢前台服务：交给 WorkManager 等约束满足后续传（位图已落盘）。
        if (carrierFor(foreground = true) == Carrier.DOZE_STOPPED) {
            DeferredSyncWorker.enqueue(context, intentId, url)
            return
        }
        ensureService()
        jobs[intentId] = scope.launch { execute(intentId, url) }
    }

    /**
     * T2.2：Carrier 选择。用户主动下载走前台服务；
     * 后台续传在 UIDT 可用时靠 dataSync 的长窗口，否则退回 Worker 自己的执行窗口。
     */
    private fun carrierFor(foreground: Boolean): Carrier = LifecycleManager.selectCarrier(
        LifecycleContext(
            userInitiated = true,
            appForeground = foreground,
            uidtAvailable = PlatformProbe.isUidtSupported(),
            deferrable = !foreground,
            inDoze = inDoze()
        )
    )

    /** 下载执行体：前台服务与 WorkManager 两条 Carrier 共用，区别只在谁负责保活。 */
    private suspend fun execute(intentId: String, url: String) {
        val startedAt = System.currentTimeMillis()
        val intentSize = intentDao.getById(intentId)?.totalSize
        SessionTelemetry.begin(intentId, topologySettings.resolve(intentSize).name)
        try {
            val coverage = coverages.computeIfAbsent(intentId) { RangeCoverage() }
            chunkDao.getByIntent(intentId)
                .filter { it.state == ChunkState.DONE.name }
                .forEach { coverage.add(it.startByte..it.endByte) }
            intentDao.updateState(intentId, IntentState.ACTIVE.name, System.currentTimeMillis())
            val descriptor = descriptors[intentId] ?: loadDescriptor(intentId)
            val routes = routesFor(intentId, url)
            val linkCount = routes.mapNotNull { it.linkId }.distinct().size
            // 单链路 16 连接，双链路 24；由 TransferLimiter 作为进程级上限。
            val concurrency = if (linkCount >= 2) 24 else 16
            TransferLimiter.updateGlobalLimit(concurrency)
            engine.download(
                intentId = intentId,
                url = url,
                destPath = destPathOf(intentId, url),
                concurrency = concurrency,
                linkCount = linkCount.coerceAtLeast(1),
                valueMap = descriptor?.valueMap,
                availability = descriptor?.availabilityContract,
                onProgress = { p ->
                    val now = System.currentTimeMillis()
                    val last = progressEmitAt[intentId] ?: 0L
                    val force = p.downloadedBytes >= p.totalBytes
                    if (force || now - last >= PROGRESS_INTERVAL_MS) {
                        progressEmitAt[intentId] = now
                        val prev = speedSamples[intentId]
                        if (prev != null) {
                            val elapsed = now - prev.second
                            if (elapsed > 0) {
                                val speed = (p.downloadedBytes - prev.first)
                                    .coerceAtLeast(0L) * 1000 / elapsed
                                speeds.update { it + (intentId to speed) }
                            }
                        }
                        speedSamples[intentId] = p.downloadedBytes to now
                        progress.update { it + (intentId to p.downloadedBytes) }
                    }
                },
                onAvailable = {
                    unlockState[intentId] = true
                    runCatching { notifyAvailable(intentId, url, descriptor) }
                },
                onChunkDone = { chunk ->
                    coverages.computeIfAbsent(intentId) { RangeCoverage() }
                        .add(chunk.start..chunk.endInclusive)
                    // 播放器可能在等待该区间，立即唤醒，避免固定 150ms 轮询。
                    RangeReadySignal.signal()
                },
                routes = routes
            )
            completedIntents.add(intentId)
            runCatching { publishCompleted(intentId, url) }
            runCatching { notifyCompleted(intentId, url) }
            recordEvent(
                intentId = intentId,
                event = "COMPLETED",
                cause = null,
                bytes = fileOf(intentId, url).length(),
                startedAt = startedAt
            )
        } catch (e: CancellationException) {
            intentDao.updateState(intentId, IntentState.PAUSED.name, System.currentTimeMillis())
            recordEvent(
                intentId = intentId,
                event = "PAUSED",
                cause = null,
                bytes = progress.value[intentId] ?: 0L,
                startedAt = startedAt
            )
            throw e
        } catch (e: Exception) {
            intentDao.updateState(intentId, IntentState.FAILED.name, System.currentTimeMillis())
            recordEvent(
                intentId = intentId,
                event = "FAILED",
                cause = (e as? DownloadException)?.failureCause?.name,
                bytes = fileOf(intentId, url).length(),
                startedAt = startedAt
            )
        } finally {
            jobs.remove(intentId)
            progressEmitAt.remove(intentId)
            speedSamples.remove(intentId)
            speeds.update { it - intentId }
            SessionTelemetry.finish(intentId)
            if (jobs.isEmpty()) stopService()
        }
    }

    fun pause(intentId: String) {
        jobs[intentId]?.cancel()
        DeferredSyncWorker.cancel(context, intentId)
        UidtScheduler.cancel(context, intentId)
    }

    /** Android 15+ dataSync 前台服务到达 6 小时上限时，保存并停止，不触发系统异常。 */
    fun onServiceTimeout() {
        jobs.keys.toList().forEach { pause(it) }
    }

    fun resume(intentId: String, url: String) {
        scope.launch {
            // 用户主动重试才重置 INVALIDATED；进程自动续传不重置，避免失败任务无限复活。
            chunkDao.getByIntent(intentId)
                .filter { it.state == ChunkState.INVALIDATED.name }
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
            start(intentId, url)
        }
    }

    /**
     * 供 WorkManager 承载的可延迟补齐：在非计费网络下继续未完成任务。
     * 返回 true 表示已推进（Worker 记 success），false 表示应重试。
     */
    suspend fun resumeDeferred(intentId: String, url: String): Boolean {
        val carrier = carrierFor(foreground = false)
        if (carrier == Carrier.DOZE_STOPPED) return false
        if (jobs[intentId]?.isActive == true) return true
        val intent = intentDao.getById(intentId) ?: return true
        if (intent.state == IntentState.COMPLETED.name) return true
        // 用户主动暂停的任务不能被后台 Worker/UIDT 复活。
        if (intent.state == IntentState.PAUSED.name) return true

        if (carrier == Carrier.UIDT) {
            if (UidtScheduler.schedule(context, intentId, url, intent.totalSize ?: 0L)) {
                return true
            }
        }
        return runDeferred(intentId, url, carrier)
    }

    /** UIDT JobService 的直接执行入口：只执行，不再调度 UIDT，避免自我循环。 */
    suspend fun executeDeferred(intentId: String, url: String): Boolean {
        val carrier = carrierFor(foreground = false)
        if (carrier == Carrier.DOZE_STOPPED) return false
        if (jobs[intentId]?.isActive == true) return true
        val intent = intentDao.getById(intentId) ?: return true
        if (intent.state == IntentState.COMPLETED.name) return true
        if (intent.state == IntentState.PAUSED.name) return true
        return runDeferred(intentId, url, carrier)
    }

    private suspend fun runDeferred(
        intentId: String,
        url: String,
        carrier: Carrier
    ): Boolean {
        if (carrier == Carrier.UIDT || carrier == Carrier.FOREGROUND_SERVICE) {
            // 这两种 Carrier 只能延长进程存活；拉起失败（如后台 FGS 限制）时
            // 退回 Worker 自带的执行窗口，任务仍然推进。
            runCatching { ensureService() }
        }
        val job = scope.launch { execute(intentId, url) }
        jobs[intentId] = job
        job.join()
        return intentDao.getById(intentId)?.state == IntentState.COMPLETED.name
    }

    /**
     * 进程被系统杀掉后，DB 中的 ACTIVE 任务不会收到 CancellationException，状态会残留为 ACTIVE。
     * 启动时把这些「中断的任务」交给 WorkManager 兜底，等约束满足再续传。
     */
    fun scheduleDeferredResume() {
        scope.launch {
            intentDao.getByState(IntentState.ACTIVE.name).forEach { intent ->
                if (jobs[intent.id]?.isActive != true) {
                    DeferredSyncWorker.enqueue(context, intent.id, intent.sourceUrl)
                }
            }
        }
    }

    private fun inDoze(): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.isDeviceIdleMode == true

    fun openIntent(task: TaskUi): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            fileOf(task.id, task.sourceUrl)
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMime(task))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun filePath(task: TaskUi): String = fileOf(task.id, task.sourceUrl).absolutePath

    /** 播放器打开前从 DB 预热覆盖，包含裂解子块；跨进程重启后不能只靠 plan 位图。 */
    suspend fun warmCoverage(intentId: String) {
        val target = coverages.computeIfAbsent(intentId) { RangeCoverage() }
        chunkDao.getByIntent(intentId)
            .filter { it.state == ChunkState.DONE.name }
            .forEach { target.add(it.startByte..it.endByte) }
    }

    fun isRangeDownloaded(intentId: String, start: Long, endInclusive: Long): Boolean {
        if (completedIntents.contains(intentId)) return true
        // 播放器可能在「暂停中」或「进程重启后」打开：此时内存覆盖表为空，
        // 从位图重建一次，否则已落盘的区间会被当成未下载而一直等待。
        val coverage = coverages[intentId] ?: buildCoverageFromBitmap(intentId)?.also {
            coverages.putIfAbsent(intentId, it)
        } ?: return false
        return coverage.covers(start, endInclusive)
    }

    private suspend fun recordEvent(
        intentId: String,
        event: String,
        cause: String?,
        bytes: Long,
        startedAt: Long
    ) {
        val now = System.currentTimeMillis()
        runCatching {
            eventDao.insert(
                TransferEventEntity(
                    intentId = intentId,
                    event = event,
                    cause = cause,
                    bytes = bytes.coerceAtLeast(0),
                    elapsedMs = (now - startedAt).coerceAtLeast(0),
                    createdAt = now
                )
            )
        }
    }

    private fun buildCoverageFromBitmap(intentId: String): RangeCoverage? {
        if (completedIntents.contains(intentId)) return RangeCoverage().apply { add(0L..Long.MAX_VALUE) }
        val total = totalSizes[intentId] ?: return null
        val plan = DownloadPlan.create(total)
        val bitmap = bitmaps.load(intentId, plan.chunks.size)
        val coverage = RangeCoverage()
        plan.chunks.forEach { chunk ->
            if (bitmap.isDone(chunk.index)) coverage.add(chunk.start..chunk.endInclusive)
        }
        return coverage
    }

    private fun resolvedMime(task: TaskUi): String {
        val name = task.fileName.lowercase()
        return when {
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            name.endsWith(".mp4") -> "video/mp4"
            name.endsWith(".pdf") -> "application/pdf"
            task.mimeType != null -> task.mimeType
            else -> "*/*"
        }
    }

    fun deleteTask(task: TaskUi) {
        pause(task.id)
        scope.launch {
            fileOf(task.id, task.sourceUrl).delete()
            bitmaps.delete(task.id)
            chunkDao.deleteByIntent(task.id)
            intentDao.delete(task.id)
            progress.update { it - task.id }
            descriptors.remove(task.id)
            unlockState.remove(task.id)
            completedIntents.remove(task.id)
            coverages.remove(task.id)
            progressEmitAt.remove(task.id)
            speedSamples.remove(task.id)
            speeds.update { it - task.id }
            runCatching { eventDao.deleteByIntent(task.id) }
        }
    }

    /** 能力解锁通知：告诉用户「现在就可以用了」，而不必等全文下载完成。 */
    private fun notifyAvailable(intentId: String, url: String, descriptor: ContentDescriptor?) {
        val label = when (descriptor?.availabilityContract?.capability) {
            AvailabilityContract.Capability.PLAYABLE -> "现在可以播放"
            AvailabilityContract.Capability.INSTALLABLE -> "现在可以安装"
            AvailabilityContract.Capability.PREVIEWABLE -> "现在可以预览"
            else -> "关键内容已就绪"
        }
        val notification = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(label)
            .setContentText(fileNameOf(url))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(intentId.hashCode() + 1, notification)
    }

    private suspend fun loadDescriptor(intentId: String): ContentDescriptor? {
        val intent = intentDao.getById(intentId) ?: return null
        val valueMap = intent.valueMapJson
            ?.let { runCatching { json.decodeFromString<ValueMap>(it) }.getOrNull() }
        val availability = decodeAvailability(intent.availabilityContractJson) ?: return null
        return ContentDescriptor(
            contentId = intent.contentId,
            totalSize = intent.totalSize ?: 0L,
            mimeType = intent.mimeType.orEmpty(),
            valueMap = valueMap,
            availabilityContract = availability,
            sinkSpec = com.yadown.core.semantic.SinkSpec(fileNameOf(intent.sourceUrl))
        )
    }

    private fun decodeAvailability(jsonText: String?): AvailabilityContract? =
        jsonText?.let { runCatching { json.decodeFromString<AvailabilityContract>(it) }.getOrNull() }

    /** P2/T2.4：完成后把私有目录文件迁移到公共媒体库；失败时保留私有副本。 */
    private suspend fun publishCompleted(intentId: String, url: String) {
        val file = fileOf(intentId, url)
        val name = fileNameOf(url)
        val intent = intentDao.getById(intentId) ?: return
        val expected = intent.totalSize ?: file.length()
        if (intent.rangeSupported) {
            val report = CompletionVerifier.verify(
                totalSize = expected,
                chunks = chunkDao.getByIntent(intentId),
                fileLength = file.length()
            )
            if (!report.complete) return
        } else if (file.length() != expected) {
            return
        }
        storageTiering.publishToPublic(
            file = file,
            displayName = name,
            expectedSize = expected,
            mimeType = mimeOf(name)
        )
    }

    private fun mimeOf(fileName: String): String {
        val name = fileName.lowercase()
        return when {
            name.endsWith(".apk") -> "application/vnd.android.package-archive"
            name.endsWith(".mp4") || name.endsWith(".m4v") -> "video/mp4"
            name.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    /**
     * 只接纳能证明与主源同一份字节的镜像：大小、Range 能力一致，
     * 且至少有一个匹配的强校验器（ETag 或 Last-Modified）。
     */
    private suspend fun verifyMirrors(
        primary: ProbeResult,
        primaryUrl: String,
        mirrors: List<String>
    ): List<String> = coroutineScope {
        mirrors.asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != primaryUrl }
            .distinct()
            .map { mirror ->
                async {
                    val candidate = runCatching { source.probe(mirror) }.getOrNull()
                    mirror.takeIf { candidate != null && ContentIdentity.matches(primary, candidate) }
                }
            }
            .toList()
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun routesFor(intentId: String, url: String): List<SourceRoute> {
        val intent = intentDao.getById(intentId) ?: return emptyList()
        val endpoints = buildList {
            addAll(linkRegistry.endpointsFor(url, SourceKind.ORIGIN))
            decodeMirrors(intent.mirrorUrlsJson).forEach { mirror ->
                addAll(linkRegistry.endpointsFor(mirror, SourceKind.MIRROR))
            }
            addAll(peerEndpoints(intent))
        }
        // 源健康读取失败只影响排序，不能阻断下载。
        return runCatching { sourceRouter.rank(endpoints) }.getOrDefault(emptyList())
    }

    /**
     * 局域网 peer 只作为附加源：查询与身份校验失败时静默跳过，不影响主源下载。
     * 超时约束避免对端离线时把任务启动拖慢。
     */
    private suspend fun peerEndpoints(intent: IntentEntity): List<SourceEndpoint> =
        withTimeoutOrNull(PEER_LOOKUP_TIMEOUT_MS) {
            runCatching { peerSourceProvider.endpointsFor(intent) }.getOrDefault(emptyList())
        } ?: emptyList()

    private fun decodeMirrors(text: String?): List<String> =
        text?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrNull() }
            .orEmpty()

    private fun notifyCompleted(intentId: String, url: String) {
        val file = fileOf(intentId, url)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val open = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val pending = PendingIntent.getActivity(
            context,
            intentId.hashCode(),
            open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, DownloadService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("下载完成")
            .setContentText(fileNameOf(url))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        NotificationManagerCompat.from(context).notify(intentId.hashCode(), notification)
    }

    private fun fileOf(intentId: String, url: String): File =
        File(destPathOf(intentId, url))

    private fun ensureService() {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun stopService() {
        context.stopService(Intent(context, DownloadService::class.java))
    }

    private fun destPathOf(intentId: String, url: String): String {
        val dest = DownloadPaths.destFile(context.filesDir, intentId, url)
        dest.parentFile?.mkdirs()
        return dest.absolutePath
    }

    private fun fileNameOf(url: String): String = DownloadPaths.fileNameOf(url)

    private fun chunkId(intentId: String, index: Int): String = "$intentId-$index"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val PROGRESS_INTERVAL_MS = 200L
        const val PEER_LOOKUP_TIMEOUT_MS = 2_000L
    }
}
