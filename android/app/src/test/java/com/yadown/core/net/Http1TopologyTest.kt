package com.yadown.core.net

import com.yadown.core.support.HttpResponse
import com.yadown.core.support.TestHttpServer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输拓扑 A/B 的容器级验证：多连接模式必须真的落在不同 TCP 上。
 *
 * 本地 server 只支持明文 HTTP/1.1，因此这里验证「H1 客户端 + 并发分块 → 多条连接」，
 * H2 单连接对照组依赖真机/外网服务器，不在容器内伪造。
 */
class Http1TopologyTest {

    private val totalSize = 256L * 1024

    @Test
    fun multiConnectionClientNegotiatesHttp11Only() {
        val reuse = OkHttpRangeSource.createClient()
        val multi = OkHttpRangeSource.createClient(http1Only = true)

        assertTrue("默认客户端应保留 HTTP/2 协商", Protocol.HTTP_2 in reuse.protocols)
        assertEquals(listOf(Protocol.HTTP_1_1), multi.protocols)
    }

    @Test
    fun concurrentChunksOccupySeparateTcpConnections() {
        val chunkSize = 64 * 1024
        val server = TestHttpServer(keepAlive = true) { request ->
            // 每个请求停 150ms，保证 4 个分块同时在途；否则 HTTP/1.1 会顺序复用同一条连接。
            Thread.sleep(150)
            val range = RANGE.find(request.headers["range"] ?: "")
                ?: return@TestHttpServer HttpResponse(code = 416)
            val start = range.groupValues[1].toLong()
            val end = range.groupValues[2].toLong()
            HttpResponse(
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes $start-$end/$totalSize",
                    "ETag" to "\"v1\"",
                    "Content-Type" to "application/octet-stream"
                ),
                body = ByteArray((end - start + 1).toInt()) { (it % 251).toByte() }
            )
        }
        val client = OkHttpRangeSource.createClient(http1Only = true)
        try {
            val source = OkHttpRangeSource(client)
            runBlocking {
                (0 until 4).map { index ->
                    async {
                        val start = index * chunkSize.toLong()
                        source.readRangeChecked(
                            url = server.baseUrl,
                            start = start,
                            endInclusive = start + chunkSize - 1,
                            totalSize = totalSize
                        )
                    }
                }.awaitAll()
            }

            assertEquals(4, server.requests.size)
            assertTrue(
                "并发分块应各自占用 TCP，实际 ${server.connectionCount} 条",
                server.connectionCount >= 2
            )
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.close()
        }
    }

    private companion object {
        val RANGE = Regex("""bytes=(\d+)-(\d+)""")
    }
}
