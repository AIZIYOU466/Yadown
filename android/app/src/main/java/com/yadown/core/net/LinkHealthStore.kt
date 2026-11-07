package com.yadown.core.net

/**
 * 链路健康度：EWMA 吞吐/延迟/失败率 + 连续失败熔断。
 *
 * 连续失败达到阈值后冷却 30s，冷却结束放行一次探测；成功即清零，失败重新冷却。
 * 该状态只在内存中维护：链路是网络环境的瞬时属性，进程重启后重新探测即可。
 */
class LinkHealthStore(
    private val clock: () -> Long = System::currentTimeMillis,
    private val alpha: Double = 0.3
) {

    data class LinkHealth(
        val id: String,
        val throughput: Double = 0.0,
        val latencyMs: Double = 0.0,
        val failureRate: Double = 0.0,
        val consecutiveFailures: Int = 0,
        val cooldownUntil: Long = 0L,
        val updatedAt: Long = 0L
    )

    private val lock = Any()
    private val health = mutableMapOf<String, LinkHealth>()

    fun recordSuccess(id: String, bytes: Long, elapsedMs: Long) {
        val now = clock()
        synchronized(lock) {
            val previous = health[id]
            val throughput = if (elapsedMs > 0) bytes * 1000.0 / elapsedMs else 0.0
            health[id] = if (previous == null) {
                LinkHealth(id = id, throughput = throughput, latencyMs = elapsedMs.toDouble(), updatedAt = now)
            } else {
                previous.copy(
                    throughput = alpha * throughput + (1 - alpha) * previous.throughput,
                    latencyMs = alpha * elapsedMs + (1 - alpha) * previous.latencyMs,
                    failureRate = previous.failureRate * (1 - alpha),
                    consecutiveFailures = 0,
                    cooldownUntil = 0L,
                    updatedAt = now
                )
            }
        }
    }

    fun recordFailure(id: String) {
        val now = clock()
        synchronized(lock) {
            val previous = health[id] ?: LinkHealth(id = id, updatedAt = now)
            val consecutive = previous.consecutiveFailures + 1
            health[id] = previous.copy(
                failureRate = alpha + (1 - alpha) * previous.failureRate,
                consecutiveFailures = consecutive,
                cooldownUntil = if (consecutive >= FAILURE_THRESHOLD) {
                    now + COOLDOWN_MS
                } else {
                    previous.cooldownUntil
                },
                updatedAt = now
            )
        }
    }

    fun isUsable(id: String): Boolean = synchronized(lock) {
        val current = health[id] ?: return true
        current.cooldownUntil <= clock()
    }

    fun get(id: String): LinkHealth? = synchronized(lock) { health[id] }

    fun snapshot(): List<LinkHealth> = synchronized(lock) {
        health.values.sortedByDescending { it.throughput }
    }

    fun score(id: String): Double = synchronized(lock) {
        val current = health[id] ?: return NEUTRAL_SCORE
        val throughputScore = (current.throughput / TARGET_THROUGHPUT).coerceIn(0.0, 1.0)
        (1.0 - current.failureRate.coerceIn(0.0, 1.0)) * 0.6 + throughputScore * 0.4
    }

    companion object {
        const val FAILURE_THRESHOLD = 2
        const val COOLDOWN_MS = 30_000L
        const val TARGET_THROUGHPUT = 1_000_000.0
        const val NEUTRAL_SCORE = 0.5
    }
}
