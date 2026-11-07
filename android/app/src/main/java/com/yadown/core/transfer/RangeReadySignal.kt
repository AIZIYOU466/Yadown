package com.yadown.core.transfer

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 区间就绪通知：下载完成一块时唤醒等待中的播放器 DataSource，
 * 替代固定 150ms 轮询，减少 seek 到未下载区间时的卡顿。
 */
object RangeReadySignal {

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()

    fun signal() {
        lock.withLock { condition.signalAll() }
    }

    /** 等待通知或超时；超时后调用方重新检查区间是否已下载。 */
    fun await(timeoutMs: Long) {
        lock.withLock {
            runCatching { condition.await(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }
}
