package com.yadown.core.transfer

import java.net.URI

/**
 * 进程级传输闸门：全局上限由本任务的会话预算驱动（单链路 16、双链路 24、MULTI 为档位），
 * 同一「主机 + 链路」的 per-key 上限与它同源，只用于显式收紧。
 *
 * 单任务的 DispatcherPolicy 只决定本任务并发，不能替代系统级准入；
 * per-key 若另取一套上限（例如源健康度的连接上限），两者取 min 会让会话预算形同虚设，
 * 也会让「连接档位 A/B」测到的始终是更小的那个值。
 */
object TransferLimiter {

    private const val DEFAULT_GLOBAL_REQUESTS = 16

    private val limiter = DynamicLimiter(DEFAULT_GLOBAL_REQUESTS, DEFAULT_GLOBAL_REQUESTS)

    suspend fun <T> withPermit(
        url: String,
        linkId: String? = null,
        perKeyLimit: Int,
        block: suspend () -> T
    ): T {
        val key = keyOf(url, linkId)
        limiter.acquire(key, perKeyLimit)
        try {
            return block()
        } finally {
            limiter.release(key)
        }
    }

    suspend fun updateGlobalLimit(limit: Int) {
        limiter.updateGlobalLimit(limit)
    }

    suspend fun snapshot(): DynamicLimiter.Snapshot = limiter.snapshot()

    fun keyOf(url: String, linkId: String?): String {
        val host = hostOf(url)
        return if (linkId.isNullOrBlank()) host else "$host@$linkId"
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
}
