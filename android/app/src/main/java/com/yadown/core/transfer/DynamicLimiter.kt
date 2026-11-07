package com.yadown.core.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 可调并发闸门：全局上限按设备档位/链路数动态设置，per-key 上限由 source_health 学习值传入。
 *
 * 等待者用 CompletableDeferred 排队；释放时唤醒全部等待者重新竞争。
 * 等待者数量受全局上限约束（个位数），惊群成本可忽略，换来实现简单、无丢失唤醒。
 */
class DynamicLimiter(
    initialGlobalLimit: Int,
    private val defaultPerKeyLimit: Int
) {

    data class Snapshot(
        val globalLimit: Int,
        val globalInUse: Int,
        val perKeyInUse: Map<String, Int>
    )

    private val mutex = Mutex()
    private var globalLimit = initialGlobalLimit.coerceAtLeast(1)
    private var globalInUse = 0
    private val perKeyLimits = mutableMapOf<String, Int>()
    private val perKeyInUse = mutableMapOf<String, Int>()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()

    suspend fun acquire(key: String, perKeyLimit: Int = defaultPerKeyLimit) {
        while (true) {
            val deferred = mutex.withLock {
                perKeyLimits[key] = perKeyLimit.coerceAtLeast(1)
                if (canAcquire(key)) {
                    take(key)
                    null
                } else {
                    CompletableDeferred<Unit>().also { waiters.addLast(it) }
                }
            }
            if (deferred == null) return
            deferred.await()
        }
    }

    suspend fun release(key: String) {
        mutex.withLock {
            if (globalInUse > 0) globalInUse--
            val current = perKeyInUse[key] ?: return@withLock
            if (current <= 1) perKeyInUse.remove(key) else perKeyInUse[key] = current - 1
            wakeAll()
        }
    }

    suspend fun updateGlobalLimit(limit: Int) {
        mutex.withLock {
            globalLimit = limit.coerceAtLeast(1)
            wakeAll()
        }
    }

    suspend fun snapshot(): Snapshot = mutex.withLock {
        Snapshot(globalLimit, globalInUse, perKeyInUse.toMap())
    }

    private fun canAcquire(key: String): Boolean =
        globalInUse < globalLimit &&
            (perKeyInUse[key] ?: 0) < (perKeyLimits[key] ?: defaultPerKeyLimit)

    private fun take(key: String) {
        globalInUse++
        perKeyInUse[key] = (perKeyInUse[key] ?: 0) + 1
    }

    private fun wakeAll() {
        if (waiters.isEmpty()) return
        val pending = waiters.toList()
        waiters.clear()
        pending.forEach { it.complete(Unit) }
    }
}
