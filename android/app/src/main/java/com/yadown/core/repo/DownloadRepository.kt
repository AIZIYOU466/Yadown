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
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.net.RangeSource
import com.yadown.core.semantic.AvailabilityContract
import com.yadown.core.semantic.ContentDescriptor
import com.yadown.core.semantic.FormatResolverRegistry
import com.yadown.core.semantic.ValueMap
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.ChunkState
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.DownloadPlan
import com.yadown.core.transfer.IntentState
import com.yadown.core.transfer.RangeCoverage
import com.yadown.core.survival.Carrier
import com.yadown.core.survival.DeferredSyncWorker
import com.yadown.core.survival.LifecycleContext
import com.yadown.core.survival.LifecycleManager
import com.yadown.core.survival.PlatformProbe
import com.yadown.core.survival.StorageTiering
import com.yadown.service.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val availableNow: Boolean
)

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: DownloadEngine,
    private val source: RangeSource,
    private val bitmaps: BitmapStore,
    private val resolvers: FormatResolverRegistry,
    private val intentDao: IntentDao,
    private val chunkDao: ChunkDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speedSamples = ConcurrentHashMap<String, Pair<Long, Long>>()
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
                        unlockState[intent.id] == true
                )
            }
        }

    suspend fun createAndStart(url: String): String {
        // 低存储时提前失败，避免下载到一半写满分区。
        if (PlatformProbe.probeStorageCapability(context).lowStorage) {
            error("存储空间不足，请清理后重试")
        }
        val probe = source.probe(url)
        val total = probe.totalSize ?: error("无法确定文件大小，服务器未返回 Content-Length/Content-Range")
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
                state = IntentState.ACTIVE.name,
                createdAt = now,
                updatedAt = now
            )
        )

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

        start(id, url)
        return id
    }

    fun start(intentId: String, url: String) {
        if (jobs[intentId]?.isActive == true) return
        // 手动启动优先于后台兜底，避免同一任务被 WorkManager 与前台服务同时拉取。
        DeferredSyncWorker.cancel(context, intentId)
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
        try {
            val coverage = coverages.computeIfAbsent(intentId) { RangeCoverage() }
            chunkDao.getByIntent(intentId)
                .filter { it.state == ChunkState.DONE.name }
                .forEach { coverage.add(it.startByte..it.endByte) }
            intentDao.updateState(intentId, IntentState.ACTIVE.name, System.currentTimeMillis())
            val descriptor = descriptors[intentId] ?: loadDescriptor(intentId)
            engine.download(
                intentId = intentId,
                url = url,
                destPath = destPathOf(intentId, url),
                valueMap = descriptor?.valueMap,
                availability = descriptor?.availabilityContract,
                onProgress = { p ->
                    val now = System.currentTimeMillis()
                    val prev = speedSamples[intentId]
                    if (prev != null) {
                        val elapsed = now - prev.second
                        if (elapsed > 0) {
                            val speed = (p.downloadedBytes - prev.first).coerceAtLeast(0L) * 1000 / elapsed
                            speeds.update { it + (intentId to speed) }
                        }
                    }
                    speedSamples[intentId] = p.downloadedBytes to now
                    progress.update { it + (intentId to p.downloadedBytes) }
                },
                onAvailable = {
                    unlockState[intentId] = true
                    runCatching { notifyAvailable(intentId, url, descriptor) }
                },
                onChunkDone = { chunk ->
                    coverages.computeIfAbsent(intentId) { RangeCoverage() }
                        .add(chunk.start..chunk.endInclusive)
                }
            )
            completedIntents.add(intentId)
            runCatching { publishCompleted(intentId, url) }
            runCatching { notifyCompleted(intentId, url) }
        } catch (e: CancellationException) {
            intentDao.updateState(intentId, IntentState.PAUSED.name, System.currentTimeMillis())
            throw e
        } catch (_: Exception) {
            intentDao.updateState(intentId, IntentState.FAILED.name, System.currentTimeMillis())
        } finally {
            jobs.remove(intentId)
            speedSamples.remove(intentId)
            speeds.update { it - intentId }
            if (jobs.isEmpty()) stopService()
        }
    }

    fun pause(intentId: String) {
        jobs[intentId]?.cancel()
    }

    fun resume(intentId: String, url: String) {
        start(intentId, url)
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

    fun isRangeDownloaded(intentId: String, start: Long, endInclusive: Long): Boolean {
        if (completedIntents.contains(intentId)) return true
        // 播放器可能在「暂停中」或「进程重启后」打开：此时内存覆盖表为空，
        // 从位图重建一次，否则已落盘的区间会被当成未下载而一直等待。
        val coverage = coverages[intentId] ?: buildCoverageFromBitmap(intentId)?.also {
            coverages.putIfAbsent(intentId, it)
        } ?: return false
        return coverage.covers(start, endInclusive)
    }

    private fun buildCoverageFromBitmap(intentId: String): RangeCoverage? {
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
        val expected = intentDao.getById(intentId)?.totalSize ?: file.length()
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
        val dir = File(context.filesDir, "downloads").apply { mkdirs() }
        return File(dir, "${intentId}_${fileNameOf(url)}").absolutePath
    }

    private fun fileNameOf(url: String): String {
        val path = runCatching { java.net.URI(url).path }.getOrNull().orEmpty()
        return path.substringAfterLast('/').ifBlank { "download.bin" }
    }

    private fun chunkId(intentId: String, index: Int): String = "$intentId-$index"

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
