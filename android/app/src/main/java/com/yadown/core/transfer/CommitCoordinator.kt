package com.yadown.core.transfer

import com.yadown.core.survival.FsyncGate

/**
 * DONE 提交协调器：维持「DB 标记 DONE 的块，其字节已持久化」这一不变式。
 *
 * 完成块先进入内存队列，每 [FsyncGate] 周期（默认 2s）或下载结束时：
 * 先 `pipeline.flush()`（等待写完 + fdatasync），再批量写 DB DONE，最后批量保存位图。
 * 掉电时未提交的块 DB 仍为 RUNNING/PENDING，重启后重下；宁可重下，不产生假完成。
 */
class CommitCoordinator(
    private val intentId: String,
    private val chunkDao: com.yadown.core.db.dao.ChunkDao,
    private val bitmaps: BitmapStore,
    private val bitmap: ChunkBitmap,
    private val fingerprint: String?,
    private val pipeline: WritePipeline,
    private val fsyncGate: FsyncGate = FsyncGate(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class DoneRecord(
        val id: String,
        val startedAt: Long,
        val completedAt: Long,
        val checksum: Long
    )

    private val lock = Any()
    private val pending = mutableListOf<DoneRecord>()
    private var bitmapDirty = false

    fun enqueue(record: DoneRecord) {
        synchronized(lock) {
            pending += record
            bitmapDirty = true
        }
    }

    /**
     * 返回 true 表示本轮有提交；false 表示未到提交窗口或提交失败（批次保留待重试）。
     * [force] 为 true 时忽略时间窗口并在失败时抛出，供下载结束前的最终提交使用。
     */
    suspend fun commit(force: Boolean): Boolean {
        val now = clock()
        val batch = synchronized(lock) {
            if (!force && !fsyncGate.shouldFsync(now)) return false
            pending.toList().also { pending.clear() }
        }

        if (batch.isNotEmpty() || force) {
            try {
                // 周期提交只保证数据落盘（文件长度已预分配并 sync），最终提交才强制元数据。
                pipeline.flush(syncMetadata = force)
            } catch (t: Throwable) {
                synchronized(lock) { pending.addAll(batch) }
                if (force) throw t
                return false
            }
        }

        if (batch.isNotEmpty()) {
            try {
                batch.forEach { record ->
                    chunkDao.updateProgress(
                        record.id,
                        ChunkState.DONE.name,
                        0,
                        record.startedAt,
                        record.completedAt,
                        record.checksum,
                        null
                    )
                }
            } catch (t: Throwable) {
                synchronized(lock) { pending.addAll(batch) }
                if (force) throw t
                return false
            }
            fsyncGate.markFsynced(now)
        }

        val dirty = synchronized(lock) {
            bitmapDirty.also { if (it) bitmapDirty = false }
        }
        if (dirty) {
            // 位图是索引而非提交记录：保存失败不阻断下载，DB 仍是权威。
            runCatching { bitmaps.save(intentId, bitmap, fingerprint) }
        }
        return batch.isNotEmpty()
    }
}
