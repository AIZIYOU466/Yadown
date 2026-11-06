package com.yadown.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yadown.core.transfer.FailureCause
import com.yadown.core.transfer.RangeRequest
import com.yadown.core.transfer.RangeResponse
import com.yadown.core.transfer.RangeSession
import com.yadown.core.transfer.RangeValidator
import com.yadown.core.transfer.ValidationResult
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
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
 */
class OkHttpRangeSourceTest {

    private val servers = mutableListOf<HttpServer>()
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
        servers.forEach { it.stop(0) }
        servers.clear()
    }

    private fun serve(handler: (HttpExchange) -> Unit): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } catch (_: Exception) {
                // 客户端提前关闭（例如 200 部分区间）是预期行为。
            } finally {
                exchange.close()
            }
        }
        server.start()
        servers += server
        return "http://127.0.0.1:${server.address.port}/file"
    }

    private fun send(
        exchange: HttpExchange,
        code: Int,
        body: ByteArray,
        headers: Map<String, String> = emptyMap()
    ) {
        headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        exchange.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) {
            exchange.responseBody.use { it.write(body) }
        }
    }

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
    fun probeAnd206ReturnExpectedBytes() = runBlocking {
        val url = serve { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=0-0") {
                send(
                    exchange, 206, bytes(1),
                    mapOf("Content-Range" to "bytes 0-0/20", "ETag" to "\"v1\"")
                )
            } else {
                send(
                    exchange, 206, bytes(10),
                    mapOf("Content-Range" to "bytes 0-9/20", "ETag" to "\"v1\"")
                )
            }
        }

        val probe = source.probe(url)
        assertEquals(20L, probe.totalSize)
        assertTrue(probe.rangeSupported)

        val payload = source.readRangeChecked(url, 0, 9, 20)
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
        val url = serve {
            send(
                it, 206, bytes(1),
                mapOf("Content-Range" to "bytes 0-0/*", "Content-Length" to "1")
            )
        }
        assertNull(source.probe(url).totalSize)
    }

    @Test
    fun partialRequestReturning200IsRejectedWithoutReadingWholeBody() = runBlocking {
        val url = serve { send(it, 200, bytes(20)) }

        val payload = source.readRangeChecked(url, 0, 9, 20)
        assertEquals(0, payload.bytes.size)
        assertInvalid(
            FailureCause.SERVER_RANGE_UNSUPPORTED,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun wholeFile200IsAccepted() = runBlocking {
        val url = serve { send(it, 200, bytes(20)) }

        val payload = source.readRangeChecked(url, 0, 19, 20)
        assertEquals(20, payload.bytes.size)
        assertEquals(
            ValidationResult.Valid,
            validate(payload, RangeRequest(0, 19), RangeSession(null, null, 20))
        )
    }

    @Test
    fun rangeNotSatisfiableIsClassified() = runBlocking {
        val url = serve {
            send(it, 416, ByteArray(0), mapOf("Content-Range" to "bytes */20"))
        }

        val payload = source.readRangeChecked(url, 0, 9, 20)
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
        val url = serve {
            send(it, 429, ByteArray(0), mapOf("Retry-After" to "2"))
        }

        val payload = source.readRangeChecked(url, 0, 9, 20)
        assertEquals(429, payload.statusCode)
        assertEquals(2000L, payload.retryAfterMs)
        assertInvalid(
            FailureCause.RATE_LIMITED,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun truncated206IsRejectedAsChecksumMismatch() = runBlocking {
        val url = serve {
            send(
                it, 206, bytes(5),
                mapOf("Content-Range" to "bytes 0-9/20")
            )
        }

        val payload = source.readRangeChecked(url, 0, 9, 20)
        assertEquals(5, payload.bytes.size)
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun oversized206IsCappedAtExpectedPlusOne() = runBlocking {
        val url = serve {
            send(
                it, 206, bytes(11),
                mapOf("Content-Range" to "bytes 0-9/20")
            )
        }

        val payload = source.readRangeChecked(url, 0, 9, 20)
        assertEquals(11, payload.bytes.size)
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun encoded206IsRejected() = runBlocking {
        val url = serve {
            send(
                it, 206, bytes(10),
                mapOf(
                    "Content-Range" to "bytes 0-9/20",
                    "Content-Encoding" to "gzip"
                )
            )
        }

        val payload = source.readRangeChecked(url, 0, 9, 20)
        assertEquals("gzip", payload.contentEncoding)
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            validate(payload, RangeRequest(0, 9), RangeSession(null, null, 20))
        )
    }

    @Test
    fun sequentialReadStreamsAllBytes() = runBlocking {
        val url = serve { send(it, 200, bytes(20)) }
        var progress = 0L
        val received = ArrayList<Byte>()

        val result = source.readSequential(
            url = url,
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
