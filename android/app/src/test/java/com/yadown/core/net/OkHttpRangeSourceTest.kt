package com.yadown.core.net

import com.yadown.core.transfer.DownloadException
import com.yadown.core.transfer.FailureCause
import com.yadown.core.transfer.RangeRequest
import com.yadown.core.transfer.RangeResponse
import com.yadown.core.transfer.RangeSession
import com.yadown.core.transfer.RangeValidator
import com.yadown.core.transfer.ValidationResult
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 真实 OkHttp 路径的协议集成测试：本地 HTTP server 返回各种恶意/异常响应，
 * 验证读取上限、先校验后读、顺序回退与 RangeValidator 的联合行为。
 *
 * 不使用 com.sun.net.httpserver：AGP 单测编译类路径不包含 jdk.httpserver。
 */
class OkHttpRangeSourceTest {

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>
    )

    private data class HttpResponse(
        val code: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0)
    )

    private class TestHttpServer(
        private val handler: (HttpRequest) -> HttpResponse
    ) : Closeable {

        private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        private val acceptThread = thread(start = true, isDaemon = true) { acceptLoop() }

        val baseUrl: String
            get() = "http://127.0.0.1:${socket.localPort}/file"

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

                    val response = handler(HttpRequest(method, path, headers))
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

    private val servers = mutableListOf<TestHttpServer>()
    private lateinit var source: OkHttpRangeSource

    @Before
    fun setUp() {
        source = OkHttpRangeSource(
            OkHttpClient.Builder()
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        servers.forEach { it.close() }
        servers.clear()
    }

    private fun serve(handler: (HttpRequest) -> HttpResponse): TestHttpServer =
        TestHttpServer(handler).also { servers += it }

    private fun bytes(count: Int): ByteArray = ByteArray(count) { (it % 251).toByte() }

    private fun validate(
        payload: RangePayload,
        request: RangeRequest,
        session: RangeSession
    ): ValidationResult = RangeValidator.validate(
        RangeResponse(
            statusCode = payload.statusCode,
            contentRange = payload.contentRange,
            etag = payload.etag,
            lastModified = payload.lastModified,
            bodyLength = payload.bytes.size.toLong(),
            totalSize = payload.totalSize,
            contentEncoding = payload.contentEncoding,
            retryAfterMs = payload.retryAfterMs
        ),
        request,
        session
    )

    private fun assertInvalid(expected: FailureCause, result: ValidationResult) {
        assertTrue("expected Invalid, got $result", result is ValidationResult.Invalid)
        assertEquals(expected, (result as ValidationResult.Invalid).cause)
    }

    @Test
    fun probe403IsLinkExpired() = runBlocking {
        val server = serve {
            HttpResponse(403, mapOf("Content-Type" to "text/html"), bytes(431))
        }

        val error = runCatching { source.probe(server.baseUrl) }.exceptionOrNull()

        assertTrue("expected DownloadException, got $error", error is DownloadException)
        assertEquals(FailureCause.LINK_EXPIRED, (error as DownloadException).failureCause)
    }

    @Test
    fun probeAnd206ReturnExpectedBytes() = runBlocking {
        val server = serve { request ->
            if (request.headers["range"] == "bytes=0-0") {
                HttpResponse(
                    code = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes 0-0/20",
                        "ETag" to "\"v1\""
                    ),
                    body = bytes(1)
                )
            } else {
                HttpResponse(
                    code = 206,
                    headers = mapOf(
                        "Content-Range" to "bytes 0-9/20",
                        "ETag" to "\"v1\""
                    ),
                    body = bytes(10)
                )
            }
        }

        val probe = source.probe(server.baseUrl)
        assertEquals(20L, probe.totalSize ?: -1L)
        assertTrue(probe.rangeSupported)

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals(10, payload.bytes.size)
        val result = validate(
            payload,
            RangeRequest(0, 9),
            RangeSession("\"v1\"", null, 20)
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun unknownTotalIn206DoesNotFallBackToOneByteContentLength() = runBlocking {
        val server = serve {
            HttpResponse(
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes 0-0/*"
                ),
                body = bytes(1)
            )
        }
        assertNull(source.probe(server.baseUrl).totalSize)
    }

    @Test
    fun partialRequestReturning200IsRejectedWithoutReadingWholeBody() = runBlocking {
        val server = serve {
            HttpResponse(code = 200, body = bytes(20))
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals(0, payload.bytes.size)
        assertInvalid(
            FailureCause.SERVER_RANGE_UNSUPPORTED,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun wholeFile200IsAccepted() = runBlocking {
        val server = serve {
            HttpResponse(code = 200, body = bytes(20))
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 19, 20)
        assertEquals(20, payload.bytes.size)
        assertEquals(
            ValidationResult.Valid,
            validate(payload, RangeRequest(0, 19), RangeSession(null, null, 20))
        )
    }

    @Test
    fun rangeNotSatisfiableIsClassified() = runBlocking {
        val server = serve {
            HttpResponse(
                code = 416,
                headers = mapOf("Content-Range" to "bytes */20")
            )
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertInvalid(
            FailureCause.RANGE_NOT_SATISFIABLE,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 30))
        )
    }

    @Test
    fun tooManyRequestsCarriesRetryAfter() = runBlocking {
        val server = serve {
            HttpResponse(code = 429, headers = mapOf("Retry-After" to "2"))
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals(429, payload.statusCode)
        assertEquals(2000L, payload.retryAfterMs ?: -1L)
        assertInvalid(
            FailureCause.RATE_LIMITED,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun truncated206IsRejectedAsChecksumMismatch() = runBlocking {
        val server = serve {
            HttpResponse(
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-9/20"),
                body = bytes(5)
            )
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals(5, payload.bytes.size)
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun oversized206IsCappedAtExpectedPlusOne() = runBlocking {
        val server = serve {
            HttpResponse(
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-9/20"),
                body = bytes(11)
            )
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals(11, payload.bytes.size)
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun encoded206IsRejected() = runBlocking {
        val server = serve {
            HttpResponse(
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes 0-9/20",
                    "Content-Encoding" to "gzip"
                ),
                body = bytes(10)
            )
        }

        val payload = source.readRangeChecked(server.baseUrl, 0, 9, 20)
        assertEquals("gzip", payload.contentEncoding)
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun sequentialReadStreamsAllBytes() = runBlocking {
        val server = serve {
            HttpResponse(code = 200, body = bytes(20))
        }
        var progress = 0L
        val received = ArrayList<Byte>()

        val result = source.readSequential(
            url = server.baseUrl,
            start = 0,
            onProgress = { progress = it },
            onBytes = { chunk -> chunk.forEach { received += it } }
        )

        assertEquals(20L, result.bytesWritten)
        assertEquals(20, received.size)
        assertEquals(20L, progress)
        assertEquals(bytes(20).toList(), received)
    }
}
