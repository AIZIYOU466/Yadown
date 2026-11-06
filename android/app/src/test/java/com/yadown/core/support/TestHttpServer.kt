package com.yadown.core.support

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>
)

data class HttpResponse(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0)
)

/**
 * 纯 ServerSocket 的测试 HTTP server。
 * 不使用 com.sun.net.httpserver：AGP 单测编译类路径不包含 jdk.httpserver。
 */
class TestHttpServer(
    private val handler: (HttpRequest) -> HttpResponse
) : Closeable {

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val acceptThread = thread(start = true, isDaemon = true) { acceptLoop() }
    private val recorded = Collections.synchronizedList(mutableListOf<HttpRequest>())

    val baseUrl: String
        get() = "http://127.0.0.1:${socket.localPort}/file"

    val port: Int
        get() = socket.localPort

    val requests: List<HttpRequest>
        get() = synchronized(recorded) { recorded.toList() }

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
                val requestLine = input.readLine() ?: return
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: "/"

                val headers = mutableMapOf<String, String>()
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val separator = line.indexOf(':')
                    if (separator > 0) {
                        headers[line.substring(0, separator).trim().lowercase()] =
                            line.substring(separator + 1).trim()
                    }
                }

                val request = HttpRequest(method, path, headers)
                recorded += request
                val response = handler(request)
                val reason = when (response.code) {
                    200 -> "OK"
                    206 -> "Partial Content"
                    416 -> "Range Not Satisfiable"
                    429 -> "Too Many Requests"
                    else -> "Error"
                }
                val head = buildString {
                    append("HTTP/1.1 ${response.code} $reason\r\n")
                    response.headers.forEach { (key, value) ->
                        append("$key: $value\r\n")
                    }
                    append("Content-Length: ${response.body.size}\r\n")
                    append("Connection: close\r\n\r\n")
                }
                connection.getOutputStream().use { output ->
                    output.write(head.toByteArray(Charsets.ISO_8859_1))
                    output.write(response.body)
                    output.flush()
                }
            }
        } catch (_: IOException) {
            // 客户端提前关闭（例如 200 部分区间）是预期行为。
        }
    }
}
