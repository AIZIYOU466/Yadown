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
    override suspend fun probe(url: String): ProbeResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).executeSuspend().use { response ->
            val contentRange = response.header("Content-Range")
            ProbeResult(
                totalSize = parseTotalSize(contentRange)
                    ?: response.header("Content-Length")?.toLongOrNull(),
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
        onProgress: (Long) -> Unit
    ): RangePayload = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$endInclusive")
            .build()
        val call = client.newCall(request)
        // executeSuspend 保证协程取消时调用 call.cancel()，中断阻塞中的 body 读取。
        call.executeSuspend().use { response ->
            val body = response.body
            if (body == null) {
                RangePayload(
                    bytes = ByteArray(0),
                    statusCode = response.code,
                    contentRange = response.header("Content-Range"),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified")
                )
            } else {
                val source = body.source()
                val buffer = ByteArray(READ_BUFFER_SIZE)
                val out = ByteArrayOutputStream()
                var totalRead = 0L
                while (true) {
                    val n = source.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    totalRead += n
                    onProgress(totalRead)
                }
                RangePayload(
                    bytes = out.toByteArray(),
                    statusCode = response.code,
                    contentRange = response.header("Content-Range"),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified")
                )
            }
        }
    }

    private fun parseTotalSize(contentRange: String?): Long? {
        val match = Regex("""bytes\s+\d+-\d+/(\d+)""")
            .matchEntire(contentRange?.trim() ?: return null) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8 * 1024

        /** 工厂：20s 无数据视为 stall（与 StallWatchdog 默认一致）。 */
        fun createClient(readTimeoutMs: Long = 20_000): OkHttpClient =
            OkHttpClient.Builder()
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