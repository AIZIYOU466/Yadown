package com.yadown.core.transfer

import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Semaphore

/**
 * 进程级传输闸门：所有任务共享全局连接预算，同一主机另有上限。
 * 单任务的 DispatcherPolicy 只决定本任务并发，不能替代系统级准入。
 */
object TransferLimiter {

    private const val MAX_GLOBAL_REQUESTS = 8
    private const val MAX_PER_HOST_REQUESTS = 4

    private val global = Semaphore(MAX_GLOBAL_REQUESTS)
    private val perHost = ConcurrentHashMap<String, Semaphore>()

    suspend fun <T> withPermit(url: String, block: suspend () -> T): T {
        val host = hostOf(url)
        val hostGate = perHost.computeIfAbsent(host) { Semaphore(MAX_PER_HOST_REQUESTS) }
        global.acquire()
        try {
            hostGate.acquire()
            try {
                return block()
            } finally {
                hostGate.release()
            }
        } finally {
            global.release()
        }
    }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown"
}
