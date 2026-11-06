package com.yadown.core.repo

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.net.RangeSource
import com.yadown.core.transfer.BitmapStore
import com.yadown.core.transfer.ChunkState
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.DownloadPlan
import com.yadown.core.transfer.IntentState
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

data class TaskUi(
    val id: String,
    val sourceUrl: String,
    val fileName: String,
    val mimeType: String?,
    val state: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long
)

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: DownloadEngine,
    private val source: RangeSource,
    private val bitmaps: BitmapStore,
    private val intentDao: IntentDao,
    private val chunkDao: ChunkDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val progress = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speeds = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val speedSamples = ConcurrentHashMap<String, Pair<Long, Long>>()

    fun observeTasks(): Flow<List<TaskUi>> =
        combine(intentDao.observeAll(), progress, speeds) { intents, downloaded, speedMap ->
            intents.map { intent ->
                TaskUi(
                    id = intent.id,
                    sourceUrl = intent.sourceUrl,
                    fileName = fileNameOf(intent.sourceUrl),
                    mimeType = intent.mimeType,
                    state = intent.state,
                    downloadedBytes = downloaded[intent.id] ?: 0L,
                    totalBytes = intent.totalSize ?: 0L,
                    speedBytesPerSec = speedMap[intent.id] ?: 0L
                )
            }
        }

    suspend fun createAndStart(url: String): String {
        val probe = source.probe(url)
        val total = probe.totalSize ?: error("无法确定文件大小，服务器未返回 Content-Length/Content-Range")
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

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
                valueMapJson = null,
                availabilityContractJson = null,
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
        ensureService()
        jobs[intentId] = scope.launch {
            try {
                intentDao.updateState(intentId, IntentState.ACTIVE.name, System.currentTimeMillis())
                engine.download(intentId, url, destPathOf(intentId, url)) { p ->
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
                }
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
    }

    fun pause(intentId: String) {
        jobs[intentId]?.cancel()
    }

    fun resume(intentId: String, url: String) {
        start(intentId, url)
    }

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
}
