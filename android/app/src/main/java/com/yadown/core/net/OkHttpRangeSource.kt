package com.yadown.core.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

class OkHttpRangeSource(private val client: OkHttpClient) : RangeSource {

    /**
     * 用 `Range: bytes=0-0` 探测，而不是 HEAD：部分 CDN 对 HEAD 返回不带 Content-Length，
     * 而 206 + Content-Range 能同时给出「是否支持 Range」和总大小。
     */
    override suspend fun probe(url: String, linkId: String?): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .header("Accept-Encoding", "identity")
            .build()
        client.newCall(request).executeSuspend().use { response ->
            val contentRange = response.header("Content-Range")
            val totalFromRange = parseTotalSize(contentRange)
            // `bytes 0-0/*` 表示总大小未知；此时不能退回 Content-Length（那是 1 字节）。
            val total = totalFromRange
                ?: if (contentRange == null) response.header("Content-Length")?.toLongOrNull() else null
            ProbeResult(
                totalSize = total,
                rangeSupported = response.code == 206,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                mimeType = response.header("Content-Type")
            )
        }
    }

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload = readRangeChecked(url, start, endInclusive, null, onProgress, null)

    override suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$endInclusive")
            .header("Accept-Encoding", "identity")
            .build()

        client.newCall(request).executeSuspend().use { response ->
            val contentRange = response.header("Content-Range")
            val etag = response.header("ETag")
            val lastModified = response.header("Last-Modified")
            val contentEncoding = response.header("Content-Encoding")

            when (response.code) {
                206 -> {
                    if (!contentRangeMatches(contentRange, start, endInclusive)) {
                        payload(ByteArray(0), response, contentRange, etag, lastModified, contentEncoding)
                    } else {
                        val expected = endInclusive - start + 1
                        val bytes = readCapped(response, expected, onProgress)
                        payload(bytes, response, contentRange, etag, lastModified, contentEncoding)
                    }
                }

                200 -> {
                    // 只有确实覆盖整个文件时才允许把 200 当数据；否则立即关闭响应，绝不读全量 body。
                    val coversWholeFile = totalSize != null &&
                        start == 0L && endInclusive >= totalSize - 1
                    if (!coversWholeFile) {
                        payload(ByteArray(0), response, contentRange, etag, lastModified, contentEncoding)
                    } else {
                        val bytes = readCapped(response, totalSize!!, onProgress)
                        payload(bytes, response, contentRange, etag, lastModified, contentEncoding)
                    }
                }

                429 -> payload(
                    bytes = ByteArray(0),
                    response = response,
                    contentRange = contentRange,
                    etag = etag,
                    lastModified = lastModified,
                    contentEncoding = contentEncoding,
                    retryAfterMs = parseRetryAfter(response.header("Retry-After"))
                )

                else -> payload(
                    ByteArray(0),
                    response,
                    contentRange,
                    etag,
                    lastModified,
                    contentEncoding
                )
            }
        }
    }

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
        if (start > 0) builder.header("Range", "bytes=$start-")
        val request = builder.build()

        client.newCall(request).executeSuspend().use { response ->
            if (response.code !in 200..299) {
                throw IOException("顺序读取失败: HTTP ${response.code}")
            }
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.equals("identity", true)) {
                throw IOException("顺序下载收到 Content-Encoding=$encoding，字节长度不可信")
            }
            val body = response.body ?: throw IOException("响应没有 body")
            val source = body.source()
            val buffer = ByteArray(READ_BUFFER_SIZE * 4)
            var written = 0L
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                onBytes(buffer.copyOf(n))
                written += n
                onProgress(written)
            }
            SequentialResult(
                bytesWritten = written,
                restarted = response.code == 200 && start > 0
            )
        }
    }

    /**
     * 只读取期望长度 + 1 字节：多读的这一字节用于让校验器发现“服务器返回超长 body”，
     * 同时保证内存上限是一个分块而不是整个文件。
     */
    private fun readCapped(
        response: Response,
        expected: Long,
        onProgress: (Long) -> Unit
    ): ByteArray {
        val body = response.body ?: return ByteArray(0)
        val source = body.source()
        val limit = expected + 1
        val out = ByteArrayOutputStream(minOf(expected, MAX_BUFFER).toInt())
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L

        while (total < limit) {
            val want = minOf(buffer.size.toLong(), limit - total).toInt()
            if (want <= 0) break
            val n = source.read(buffer, 0, want)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
            onProgress(total)
        }
        return out.toByteArray()
    }

    private fun payload(
        bytes: ByteArray,
        response: Response,
        contentRange: String?,
        etag: String?,
        lastModified: String?,
        contentEncoding: String?,
        retryAfterMs: Long? = null
    ): RangePayload = RangePayload(
        bytes = bytes,
        statusCode = response.code,
        contentRange = contentRange,
        etag = etag,
        lastModified = lastModified,
        totalSize = parseTotalSize(contentRange),
        contentEncoding = contentEncoding,
        retryAfterMs = retryAfterMs,
        peerXxHash64 = parseXxHash(response.header(XXHASH_HEADER))
    )

    private fun parseXxHash(value: String?): Long? {
        val text = value?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return null
        if (text.isEmpty() || text.length > 16) return null
        return text.toLongOrNull(16)
            ?: runCatching { java.lang.Long.parseUnsignedLong(text, 16) }.getOrNull()
    }

    private fun contentRangeMatches(value: String?, start: Long, endInclusive: Long): Boolean {
        val match = CONTENT_RANGE.matchEntire(value?.trim() ?: return false) ?: return false
        val (rangeStart, rangeEnd) = match.destructured
        return rangeStart.toLongOrNull() == start && rangeEnd.toLongOrNull() == endInclusive
    }

    private fun parseTotalSize(contentRange: String?): Long? {
        val match = CONTENT_RANGE.matchEntire(contentRange?.trim() ?: return null) ?: return null
        return match.groupValues[3].toLongOrNull()
    }

    private fun parseRetryAfter(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1000).coerceAtLeast(0)
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8 * 1024
        private const val MAX_BUFFER = 4L * 1024 * 1024
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")

        /** 工厂：20s 无数据视为 stall（与 StallWatchdog 默认一致）。 */
        fun createClient(readTimeoutMs: Long = 20_000): OkHttpClient =
            OkHttpClient.Builder()
                .eventListener(ConnectionMetrics.listener)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
    }
}

private suspend fun Call.executeSuspend(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }

        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { cancel() }
}
