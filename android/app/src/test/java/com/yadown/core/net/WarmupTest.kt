package com.yadown.core.net

import com.yadown.core.support.HttpResponse
import com.yadown.core.support.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预热必须真的留下可用连接：OkHttp 默认池只保留 5 条空闲连接时，
 * 预热 N 条等于替将被裁掉的连接付握手费（真机 27 次建连就是这么来的）。
 */
class WarmupTest {

    @Test
    fun warmupOpensConnectionsAndPoolKeepsThem() = runBlocking {
        // 服务端加 30ms 延迟：4 个预热请求同时在途才会开 4 条连接；
        // 无延迟时 loopback 太快，请求会复用同一条连接，连接数不稳定。
        val server = TestHttpServer(keepAlive = true, latencyMs = 30) {
            HttpResponse(206, mapOf("Content-Range" to "bytes 0-0/1000"), ByteArray(1))
        }
        try {
            val client = OkHttpRangeSource.createClient(http1Only = true)
            val source = OkHttpRangeSource(client)

            source.warmup(server.baseUrl, 1000, 4)

            assertTrue(
                "应建立多条独立 TCP，实际 ${server.connectionCount}",
                server.connectionCount >= 3
            )
            assertEquals(
                "预热出的连接必须留在池中供首波复用",
                server.connectionCount,
                client.connectionPool.idleConnectionCount()
            )
            client.dispatcher.executorService.shutdown()
        } finally {
            server.close()
        }
    }

    @Test
    fun warmupWithSingleConnectionIsNoop() = runBlocking {
        val server = TestHttpServer(keepAlive = true) {
            HttpResponse(206, mapOf("Content-Range" to "bytes 0-0/1000"), ByteArray(1))
        }
        try {
            val source = OkHttpRangeSource(OkHttpRangeSource.createClient(http1Only = true))
            source.warmup(server.baseUrl, 1000, 1)
            assertEquals(0, server.connectionCount)
        } finally {
            server.close()
        }
    }
}
