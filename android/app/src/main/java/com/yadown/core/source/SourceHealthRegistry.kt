package com.yadown.core.source

import com.yadown.core.db.dao.SourceHealthDao
import com.yadown.core.db.entity.SourceHealthEntity
import com.yadown.core.transfer.FailureCause
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** L2 SourceHealthRegistry 契约，见 docs/ARCHITECTURE.md §5.2。 */
interface SourceHealthRegistry {

    suspend fun recordSuccess(sourceKey: String, throughput: Double, latencyMs: Double)

    suspend fun recordFailure(sourceKey: String, cause: FailureCause)

    /** 读取未过期快照；过期条目与不存在等价。 */
    suspend fun get(sourceKey: String): SourceHealth?

    suspend fun getConnectionLimit(sourceKey: String): Int?
}

/**
 * Room 持久化实现。同一源的读改写用互斥锁串行化：健康度只有一份，
 * 并发 chunk 同时成功时不能互相覆盖丢样本。
 */
class RoomSourceHealthRegistry(
    private val dao: SourceHealthDao,
    private val model: SourceHealthModel = SourceHealthModel()
) : SourceHealthRegistry {

    private val mutex = Mutex()

    override suspend fun recordSuccess(sourceKey: String, throughput: Double, latencyMs: Double) {
        mutex.withLock {
            val previous = load(sourceKey)
            dao.upsert(model.onSuccess(sourceKey, previous, throughput, latencyMs).toEntity())
        }
    }

    override suspend fun recordFailure(sourceKey: String, cause: FailureCause) {
        mutex.withLock {
            val previous = load(sourceKey)
            val next = model.onFailure(sourceKey, previous, cause) ?: return
            dao.upsert(next.toEntity())
        }
    }

    override suspend fun get(sourceKey: String): SourceHealth? = mutex.withLock {
        load(sourceKey)
    }

    override suspend fun getConnectionLimit(sourceKey: String): Int? =
        get(sourceKey)?.connectionLimit

    private suspend fun load(sourceKey: String): SourceHealth? {
        val entity = dao.get(sourceKey) ?: return null
        val health = entity.toHealth()
        return if (model.isExpired(health)) null else health
    }

    private fun SourceHealthEntity.toHealth() = SourceHealth(
        sourceKey = sourceKey,
        throughput = ewmaThroughput,
        failureRate = ewmaFailureRate,
        latencyMs = ewmaLatency,
        connectionLimit = connectionLimitLearned,
        lastUpdated = lastUpdated,
        expiresAt = ttlExpiresAt
    )

    private fun SourceHealth.toEntity() = SourceHealthEntity(
        sourceKey = sourceKey,
        ewmaThroughput = throughput,
        ewmaLatency = latencyMs,
        ewmaFailureRate = failureRate,
        connectionLimitLearned = connectionLimit,
        lastUpdated = lastUpdated,
        ttlExpiresAt = expiresAt
    )
}
