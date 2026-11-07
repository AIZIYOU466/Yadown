package com.yadown.core.net

import com.yadown.core.support.HttpResponse
import com.yadown.core.support.TestHttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 连接池容量必须容纳并发上限。
 *
 * OkHttp 默认只保留 5 条空闲连接：16 并发分块建出的 TCP 会被裁到 5 条，下一波分块重新握手。
 * 真机（23 分块 / 预算 16 / 预热 16 条）实测 27 次建连、复用率 30%，与此同源。
 */
class ConnectionPoolSizingTest {

    private val concurrency = 16
    private val totalSize = 1024L

    @Test
    fun poolKeepsConnectionsForNextWave() = runBlocking {
        val server = TestHttpServer(keepAlive = true, latencyMs = 30) { request ->
            val (start, end) = parseRange(request.headers["range"])
            HttpResponse(
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "ETag" to "\"v1\""
                ),
                body = ByteArray((end - start + 1).toInt())
            )
        }
        try {
            val client = OkHttpRangeSource.createClient(http1Only = true)
            val source = OkHttpRangeSource(client)

            wave(source, server.baseUrl)
            assertEquals(
                "第一波应为每条并发请求各建一条 TCP",
                concurrency,
                server.connectionCount
            )

            // 连接归还连接池后触发清理；默认池容量下这里会掉到 5。
            Thread.sleep(300)
            assertEquals(
                "连接池必须保留与并发上限同数量的空闲连接",
                concurrency,
                client.connectionPool.idleConnectionCount()
            )

            val beforeSecondWave = server.connectionCount
            wave(source, server.baseUrl)
            assertEquals(
                "第二波必须全部复用，不应重新握手",
                beforeSecondWave,
                server.connectionCount
            )
            client.dispatcher.executorService.shutdown()
        } finally {
            server.close()
        }
    }

    private suspend fun wave(source: OkHttpRangeSource, url: String) = coroutineScope {
        (0 until concurrency).map { index ->
            async(Dispatchers.IO) {
                source.readRangeChecked(url, index.toLong(), index.toLong(), totalSize, {}, null)
                    .statusCode
            }
        }.awaitAll()
    }

    private fun parseRange(header: String?): Pair<Long, Long> {
        val parts = header?.removePrefix("bytes=")?.split('-') ?: return 0L to 0L
        val start = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val end = parts.getOrNull(1)?.toLongOrNull() ?: start
        return start to end
    }
}
