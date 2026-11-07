package com.yadown.core.support

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    /** 服务端接受连接时分配的序号，用于断言并发请求是否落在不同 TCP 上。 */
    val connectionId: Int = -1
)

data class HttpResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
)

/**
 * 纯 ServerSocket 的测试 HTTP server。
 * 不使用 com.sun.net.httpserver：AGP 单测编译类路径不包含 jdk.httpserver。
 *
 * [keepAlive] 为 false（默认）时每个响应后关闭连接，与历史行为一致；
 * 为 true 时在一条连接上循环处理请求，用于验证连接复用/多条 TCP 的行为。
 */
class TestHttpServer(
    private val keepAlive: Boolean = false,
    /** 每连接限速（字节/秒）；0 表示不限速，用于复现单连接带宽上限。 */
    private val throttleBytesPerSec: Int = 0,
    /** 首个响应前的固定延迟，模拟 RTT/握手。 */
    private val latencyMs: Long = 0,
    private val handler: (HttpRequest) -> HttpResponse
) : Closeable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val acceptThread = thread(start = true, isDaemon = true) { acceptLoop() }
    private val recorded = Collections.synchronizedList(mutableListOf<HttpRequest>())
    private val accepted = Collections.synchronizedList(mutableListOf<Socket>())
    private val connectionIds = Collections.synchronizedSet(mutableSetOf<Int>())
    private val nextConnectionId = AtomicInteger()

    val baseUrl: String
        get() = "http://127.0.0.1:${socket.localPort}/file"

    val port: Int
        get() = socket.localPort

    val requests: List<HttpRequest>
        get() = synchronized(recorded) { recorded.toList() }

    /** 已接受的 TCP 连接数（不是请求数）。 */
    val connectionCount: Int
        get() = connectionIds.size

    override fun close() {
        runCatching { socket.close() }
        synchronized(accepted) {
            accepted.forEach { runCatching { it.close() } }
            accepted.clear()
        }
        acceptThread.join(500)
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            val connectionId = nextConnectionId.incrementAndGet()
            connectionIds += connectionId
            accepted += client
            thread(start = true, isDaemon = true) { handle(client, connectionId) }
        }
    }

    private fun handle(client: Socket, connectionId: Int) {
        try {
            client.use { connection ->
                val input = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val output = connection.getOutputStream()
                while (true) {
                    val requestLine = input.readLine() ?: return
                    val parts = requestLine.split(' ')
                    val method = parts.getOrNull(0) ?: return
                    val path = parts.getOrNull(1) ?: "/"

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = input.readLine() ?: return
                        if (line.isEmpty()) break
                        val separator = line.indexOf(':')
                        if (separator > 0) {
                            headers[line.substring(0, separator).trim().lowercase()] =
                                line.substring(separator + 1).trim()
                        }
                    }

                    val request = HttpRequest(method, path, headers, connectionId)
                    recorded += request
                    val response = handler(request)
                    if (latencyMs > 0) Thread.sleep(latencyMs)
                    val reason = when (response.code) {
                        200 -> "OK"
                        206 -> "Partial Content"
                        416 -> "Range Not Satisfiable"
                        429 -> "Too Many Requests"
                        else -> "Error"
                    }
                    val head = buildString {
                        append("HTTP/1.1 ${response.code} $reason\r\n")
                        response.headers.forEach { (key, value) -> append("$key: $value\r\n") }
                        append("Content-Length: ${response.body.size}\r\n")
                        append(if (keepAlive) "Connection: keep-alive\r\n\r\n" else "Connection: close\r\n\r\n")
                    }
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    writeBodyThrottled(output, response.body)
                    output.flush()
                    if (!keepAlive) return
                }
            }
        } catch (_: IOException) {
            // 客户端提前关闭（例如 200 部分区间）是预期行为。
        }
    }

    private fun writeBodyThrottled(output: java.io.OutputStream, body: ByteArray) {
        if (throttleBytesPerSec <= 0) {
            output.write(body)
            return
        }
        val step = 8 * 1024
        val stepMs = (step * 1000L / throttleBytesPerSec).coerceAtLeast(1)
        var offset = 0
        while (offset < body.size) {
            val n = minOf(step, body.size - offset)
            output.write(body, offset, n)
            output.flush()
            offset += n
            if (offset < body.size) Thread.sleep(stepMs)
        }
    }
}
