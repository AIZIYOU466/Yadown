package com.yadown.core.net

data class ProbeResult(
    val totalSize: Long?,
    val rangeSupported: Boolean,
    val etag: String?,
    val lastModified: String?,
    val mimeType: String?
)

/** 局域网 peer 响应携带的分块校验头名，由传输层原样解析。 */
const val XXHASH_HEADER = "X-Yadown-XxHash64"

data class RangePayload(
    val bytes: ByteArray,
    val statusCode: Int,
    val contentRange: String?,
    val etag: String?,
    val lastModified: String?,
    val totalSize: Long? = null,
    val contentEncoding: String? = null,
    val retryAfterMs: Long? = null,
    /** 局域网 peer 在响应头声明的分块 xxHash64；普通源为 null。 */
    val peerXxHash64: Long? = null
)

data class SequentialResult(
    val bytesWritten: Long,
    val restarted: Boolean
)

interface RangeSource {
    suspend fun probe(url: String, linkId: String? = null): ProbeResult

    /**
     * 读取指定 Range。[onProgress] 在收到字节时回调累计字节数（供 stall 检测），
     * 协程取消时应中断底层请求（可取消）。[linkId] 用于多链路路由，普通源忽略。
     */
    suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit = {},
        linkId: String? = null
    ): RangePayload

    /**
     * 带总大小的 Range 读取：总大小用于判断 200 是否真的覆盖整个文件，
     * 避免把“服务器忽略 Range 返回全量 body”误当成区间数据。
     */
    suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit = {},
        linkId: String? = null
    ): RangePayload = readRange(url, start, endInclusive, onProgress, linkId)

    /**
     * 非 Range 源的顺序读取。默认不支持；实现方负责把字节交给 [onBytes]，
     * 不要求在内存里缓存整个文件。
     */
    suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = throw UnsupportedOperationException("该源不支持顺序读取")

    /**
     * 预热连接：正式下载前并发建立最多 [connections] 条连接，减少首批块请求的握手等待。
     * 默认不做任何事；预热失败必须静默，不能阻断下载。
     */
    suspend fun warmup(url: String, totalSize: Long?, connections: Int) {}
}
