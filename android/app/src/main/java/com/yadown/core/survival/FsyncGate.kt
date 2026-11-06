package com.yadown.core.survival

/**
 * 全局 fsync 合并闸：fdatasync 代价高，合并到固定间隔才落盘。
 * 目标：64 并发下 fdatasync 次数 < 每 2 秒 1 次（见 docs/ARCHITECTURE.md §T2.5）。
 */
class FsyncGate(private val minIntervalMs: Long = DEFAULT_INTERVAL_MS) {
    private var lastFsyncAt = 0L

    @Synchronized
    fun shouldFsync(nowMs: Long): Boolean =
        lastFsyncAt == 0L || nowMs - lastFsyncAt >= minIntervalMs

    @Synchronized
    fun markFsynced(nowMs: Long) {
        lastFsyncAt = nowMs
    }

    companion object {
        const val DEFAULT_INTERVAL_MS = 2_000L
    }
}