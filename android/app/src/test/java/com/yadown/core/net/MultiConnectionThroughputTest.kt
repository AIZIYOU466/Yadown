package com.yadown.core.net

import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 验证「多连接叠加」不是空话：服务器对每条 TCP 单独限速时，
 * 并发连接数翻倍应让总耗时接近减半。这是 GitHub/Azure 单连接受限场景的本地代理测量。
 */
class MultiConnectionThroughputTest {

    private val totalSize = 4L * 1024 * 1024
    private val chunkSize = 256 * 1024
    private val perConnectionBytesPerSec = 200_000

    @Test
    fun moreConnectionsRaiseAggregateThroughput() = runBlocking {
        val server = ThrottledRangeServer(totalSize, perConnectionBytesPerSec)
        try {
            val client = OkHttpRangeSource.createClient(http1Only = true)
            val source = OkHttpRangeSource(client)

            val twoConnections = timeWithLimit(source, server.url, totalChunks = 4, limit = 2)
            val fourConnections = timeWithLimit(source, server.url, totalChunks = 4, limit = 4)
            client.dispatcher.executorService.shutdown()

            assertTrue(
                "4 连接 ($fourConnections ms) 应显著快于 2 连接 ($twoConnections ms)",
                fourConnections * 1.4 < twoConnections
            )
        } finally {
            server.close()
        }
    }

    /** 固定 4 个块，用 Semaphore 把同时在飞的请求限制为 [limit]，等价于引擎的 concurrency。 */
    private suspend fun timeWithLimit(
        source: OkHttpRangeSource,
        url: String,
        totalChunks: Int,
        limit: Int
    ): Long {
        val gate = Semaphore(limit)
        val startedAt = System.nanoTime()
        coroutineScope {
            (0 until totalChunks).map { i ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        val start = i.toLong() * chunkSize
                        val end = start + chunkSize - 1
                        source.readRangeChecked(url, start, end, totalSize, {}, null).bytes.size
                    }
                }
            }.awaitAll()
        }
        return (System.nanoTime() - startedAt) / 1_000_000
    }

    private class ThrottledRangeServer(
        private val totalSize: Long,
        private val bytesPerSec: Int
    ) : Closeable {

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val acceptThread = thread(start = true, isDaemon = true) { acceptLoop() }

        val url: String get() = "http://127.0.0.1:${socket.localPort}/file"

        override fun close() {
            runCatching { socket.close() }
            acceptThread.join(500)
        }

        private fun acceptLoop() {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(start = true, isDaemon = true) { handle(client) }
            }
        }

        private fun handle(client: Socket) {
            try {
                client.use { connection ->
                    val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    input.readLine() ?: return
                    var rangeHeader: String? = null
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.lowercase().startsWith("range:")) {
                            rangeHeader = line.substringAfter(':').trim()
                        }
                    }
                    val (start, end) = parseRange(rangeHeader) ?: (0L to totalSize - 1)
                    val length = (end - start + 1).toInt()
                    val head = buildString {
                        append("HTTP/1.1 206 Partial Content\r\n")
                        append("Content-Length: $length\r\n")
                        append("Content-Range: bytes $start-$end/$totalSize\r\n")
                        append("Connection: close\r\n\r\n")
                    }
                    val out = connection.getOutputStream()
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.flush()
                    val body = ByteArray(length) { (it % 251).toByte() }
                    val step = 16 * 1024
                    val stepMs = (step * 1000L / bytesPerSec).coerceAtLeast(1)
                    var offset = 0
                    while (offset < length) {
                        val n = minOf(step, length - offset)
                        out.write(body, offset, n)
                        out.flush()
                        offset += n
                        if (offset < length) Thread.sleep(stepMs)
                    }
                }
            } catch (_: Exception) {
                // 客户端提前关闭是预期行为。
            }
        }

        private fun parseRange(header: String?): Pair<Long, Long>? {
            val value = header?.removePrefix("bytes=") ?: return null
            val (s, e) = value.split('-', limit = 2)
            val start = s.toLongOrNull() ?: return null
            val end = e.toLongOrNull() ?: return null
            return start to end
        }
    }
}
