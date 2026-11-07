package com.yadown.core.source

import com.yadown.core.transfer.FailureCause

/** 单个源的跨会话健康快照，字段与 `source_health` 表一一对应。 */
data class SourceHealth(
    val sourceKey: String,
    val throughput: Double,
    val failureRate: Double,
    val latencyMs: Double,
    val connectionLimit: Int?,
    val lastUpdated: Long,
    val expiresAt: Long
)

/**
 * 源健康度的纯逻辑部分：EWMA 吞吐/延迟/失败率与连接上限学习。
 *
 * 连接上限从 4 起步，只有在失败率足够低时才扩容（上限 6），
 * 一旦出现可归因于源的失败立刻减半回滚。
 */
class SourceHealthModel(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val alpha: Double = DEFAULT_ALPHA
) {

    fun onSuccess(
        sourceKey: String,
        previous: SourceHealth?,
        throughput: Double,
        latencyMs: Double
    ): SourceHealth {
        val now = clock()
        val observedThroughput = throughput.coerceAtLeast(0.0)
        val observedLatency = latencyMs.coerceAtLeast(0.0)
        val prevThroughput = previous?.throughput
        val failureRate = if (previous == null) 0.0 else previous.failureRate * (1 - alpha)
        val nextThroughput = if (prevThroughput == null) {
            observedThroughput
        } else {
            alpha * observedThroughput + (1 - alpha) * prevThroughput
        }
        val nextLatency = previous?.let {
            alpha * observedLatency + (1 - alpha) * it.latencyMs
        } ?: observedLatency

        val currentLimit = previous?.connectionLimit ?: INITIAL_CONNECTION_LIMIT
        val nextLimit = if (failureRate <= EXPAND_FAILURE_RATE && currentLimit < MAX_CONNECTION_LIMIT) {
            currentLimit + 1
        } else {
            currentLimit
        }

        return SourceHealth(
            sourceKey = sourceKey,
            throughput = nextThroughput,
            failureRate = failureRate,
            latencyMs = nextLatency,
            connectionLimit = nextLimit,
            lastUpdated = now,
            expiresAt = now + ttlMs
        )
    }

    /**
     * 只有可归因于源的失败才写入健康度；本地存储、内容漂移、预算等失败返回 null，
     * 避免把应用侧问题算到源头上。
     */
    fun onFailure(
        sourceKey: String,
        previous: SourceHealth?,
        cause: FailureCause
    ): SourceHealth? {
        if (cause !in SOURCE_FAULTS) return null
        val now = clock()
        val prevFailure = previous?.failureRate ?: 0.0
        val failureRate = alpha + (1 - alpha) * prevFailure
        val currentLimit = previous?.connectionLimit ?: INITIAL_CONNECTION_LIMIT
        val nextLimit = if (failureRate > ROLLBACK_FAILURE_RATE) {
            maxOf(MIN_CONNECTION_LIMIT, currentLimit / 2)
        } else {
            currentLimit
        }
        return SourceHealth(
            sourceKey = sourceKey,
            throughput = previous?.throughput ?: 0.0,
            failureRate = failureRate,
            latencyMs = previous?.latencyMs ?: 0.0,
            connectionLimit = nextLimit,
            lastUpdated = now,
            expiresAt = now + ttlMs
        )
    }

    fun isExpired(health: SourceHealth, now: Long = clock()): Boolean = health.expiresAt <= now

    fun connectionLimit(health: SourceHealth?): Int =
        health?.connectionLimit ?: INITIAL_CONNECTION_LIMIT

    fun isUsable(health: SourceHealth?): Boolean =
        health == null || health.failureRate < UNUSABLE_FAILURE_RATE

    companion object {
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
        const val DEFAULT_ALPHA = 0.3
        const val INITIAL_CONNECTION_LIMIT = 4
        const val MIN_CONNECTION_LIMIT = 1
        const val MAX_CONNECTION_LIMIT = 6
        const val EXPAND_FAILURE_RATE = 0.05
        const val ROLLBACK_FAILURE_RATE = 0.2
        const val UNUSABLE_FAILURE_RATE = 0.8

        /** 可归因于源/网络路径本身的失败。 */
        val SOURCE_FAULTS = setOf(
            FailureCause.NETWORK_TIMEOUT,
            FailureCause.DNS_RESOLVE_FAILED,
            FailureCause.TLS_HANDSHAKE_FAILED,
            FailureCause.SERVER_RANGE_UNSUPPORTED,
            FailureCause.RANGE_NOT_SATISFIABLE,
            FailureCause.SERVER_SILENT_REJECTION,
            FailureCause.RATE_LIMITED,
            FailureCause.PEER_UNAVAILABLE
        )
    }
}
