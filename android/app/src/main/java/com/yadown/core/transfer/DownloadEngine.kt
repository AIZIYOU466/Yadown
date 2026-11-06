package com.yadown.core.transfer

import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.io.FileStore
import com.yadown.core.net.RangeSource
import com.yadown.core.survival.FsyncGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long)

class DownloadException(val failureCause: FailureCause, val detail: String) : Exception(detail)

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
    /**
     * 下载一个 intent。已完成分块由位图跳过，因此重复调用即可实现断点续传。
     * 取消协程即可暂停：未完成的 chunk 不会被标记 DONE，下次重新下载。
     */
    suspend fun download(
        intentId: String,
        url: String,
        destPath: String,
        concurrency: Int = 4,
        chunkSize: Long = DownloadPlan.DEFAULT_CHUNK_SIZE,
        onProgress: (DownloadProgress) -> Unit = {}
    ) = withContext(ioDispatcher) {
        require(concurrency > 0) { "concurrency 必须为正: $concurrency" }
        val intent = intentDao.getById(intentId) ?: error("intent 不存在: $intentId")
        val total = intent.totalSize ?: error("intent 缺少 totalSize: $intentId")
        val plan = DownloadPlan.create(total, chunkSize)
        val bitmap = bitmaps.load(intentId, plan.chunks.size)
        val session = RangeSession(intent.etag, intent.lastModified, total)
        val completed = AtomicLong(plan.chunks.filter { bitmap.isDone(it.index) }.sumOf { it.length() })

        onProgress(DownloadProgress(completed.get(), total))

        val bitmapMutex = Mutex()
        val semaphore = Semaphore(concurrency)

        coroutineScope {
            plan.chunks
                .filterNot { bitmap.isDone(it.index) }
                .map { chunk ->
                    async {
                        semaphore.withPermit {
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
                                onProgress = onProgress
                            )
                        }
                    }
                }
                .awaitAll()
        }

        intentDao.updateState(intentId, IntentState.COMPLETED.name, clock())
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
        onProgress: (DownloadProgress) -> Unit
    ) {
        val id = chunkId(intentId, chunk.index)
        val startedAt = clock()
        chunkDao.updateProgress(id, ChunkState.RUNNING.name, 0, startedAt, null, null, null)

        val payload = source.readRange(url, chunk.start, chunk.endInclusive)
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
                    bitmap.markDone(chunk.index)
                    bitmaps.save(intentId, bitmap)
                }
                chunkDao.updateProgress(id, ChunkState.DONE.name, 0, startedAt, clock(), null, null)
                onProgress(DownloadProgress(completed.addAndGet(chunk.length()), total))
            }
        }
    }

    private fun chunkId(intentId: String, index: Int): String = "$intentId-$index"
}
