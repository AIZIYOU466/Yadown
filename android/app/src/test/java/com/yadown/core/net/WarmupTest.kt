package com.yadown.core.net

import com.yadown.core.support.HttpResponse
import com.yadown.core.support.TestHttpServer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WarmupTest {

    @Test
    fun warmupOpensRequestedConnectionsOnHttp1() = runBlocking {
        val server = TestHttpServer(keepAlive = true) {
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

            assertEquals("应建立 4 条独立 TCP", 4, server.connectionCount)
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
