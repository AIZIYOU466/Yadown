package com.yadown.core.transfer

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore

/**
 * 进程级传输闸门：所有任务共享全局连接预算，同一「主机 + 链路」另有上限。
 * 单任务的 DispatcherPolicy 只决定本任务并发，不能替代系统级准入；
 * 链路维度独立计数，Wi-Fi 与蜂窝各可用满每主机配额。
 */
object TransferLimiter {

    private const val MAX_GLOBAL_REQUESTS = 8
    private const val MAX_PER_KEY_REQUESTS = 4

    private val global = Semaphore(MAX_GLOBAL_REQUESTS)
    private val perKey = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> withPermit(
        url: String,
        linkId: String? = null,
        block: suspend () -> T
    ): T {
        val gate = perKey.computeIfAbsent(keyOf(url, linkId)) {
            Semaphore(MAX_PER_KEY_REQUESTS)
        }
        global.acquire()
        try {
            gate.acquire()
            try {
                return block()
            } finally {
                gate.release()
            }
        } finally {
            global.release()
        }
    }

    fun keyOf(url: String, linkId: String?): String {
        val host = hostOf(url)
        return if (linkId.isNullOrBlank()) host else "$host@$linkId"
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
}
