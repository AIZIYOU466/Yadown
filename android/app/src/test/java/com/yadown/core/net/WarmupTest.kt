package com.yadown.core.net

import com.yadown.core.support.HttpResponse
import com.yadown.core.support.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarmupTest {

    @Test
    fun warmupOpensRequestedConnectionsOnHttp1() = runBlocking {
        // 服务端加 50ms 延迟：4 个预热请求同时在途才会开 4 条连接；
        // 无延迟时 loopback 太快，请求会复用同一条连接，连接数不稳定。
        val server = TestHttpServer(keepAlive = true, latencyMs = 50) {
            HttpResponse(
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-0/1000"),
                body = ByteArray(1)
            )
        }
        try {
            val client = OkHttpRangeSource.createClient(http1Only = true)
            val source = OkHttpRangeSource(client)

            source.warmup(server.baseUrl, 1000, 4)

            assertTrue(
                "应建立多条独立 TCP，实际 ${server.connectionCount}",
                server.connectionCount >= 3
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
