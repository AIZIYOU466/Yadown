package com.yadown.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

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
        client.newCall(request).execute().use { response ->
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

    override suspend fun readRange(url: String, start: Long, endInclusive: Long): RangePayload =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=$start-$endInclusive")
                .build()
            client.newCall(request).execute().use { response ->
                RangePayload(
                    bytes = response.body?.bytes() ?: ByteArray(0),
                    statusCode = response.code,
                    contentRange = response.header("Content-Range"),
                    etag = response.header("ETag"),
                    lastModified = response.header("Last-Modified")
                )
            }
        }

    private fun parseTotalSize(contentRange: String?): Long? {
        val match = Regex("""bytes\s+\d+-\d+/(\d+)""")
            .matchEntire(contentRange?.trim() ?: return null) ?: return null
        return match.groupValues[1].toLongOrNull()
    }
}
