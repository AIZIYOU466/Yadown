package com.yadown.core.transfer

import java.net.URI

/**
 * 进程级传输闸门：全局配额可调（单链路 6、双链路 12），
 * 同一「主机 + 链路」的并发上限由 source_health 学习值传入。
 * 单任务的 DispatcherPolicy 只决定本任务并发，不能替代系统级准入。
 */
object TransferLimiter {

    private const val DEFAULT_GLOBAL_REQUESTS = 8
    private const val DEFAULT_PER_KEY_REQUESTS = 4

    private val limiter = DynamicLimiter(DEFAULT_GLOBAL_REQUESTS, DEFAULT_PER_KEY_REQUESTS)

    suspend fun <T> withPermit(
        url: String,
        linkId: String? = null,
        perKeyLimit: Int = DEFAULT_PER_KEY_REQUESTS,
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
