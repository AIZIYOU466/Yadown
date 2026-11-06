package com.yadown.core.net

data class ProbeResult(
    val totalSize: Long?,
    val rangeSupported: Boolean,
    val etag: String?,
    val lastModified: String?,
    val mimeType: String?
)

data class RangePayload(
    val bytes: ByteArray,
    val statusCode: Int,
    val contentRange: String?,
    val etag: String?,
    val lastModified: String?
)

interface RangeSource {
    suspend fun probe(url: String): ProbeResult
    suspend fun readRange(url: String, start: Long, endInclusive: Long): RangePayload
}
