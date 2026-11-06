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

    /**
     * 读取指定 Range。[onProgress] 在收到字节时回调累计字节数（供 stall 检测），
     * 协程取消时应中断底层请求（可取消）。
     */
    suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit = {}
    ): RangePayload
}
