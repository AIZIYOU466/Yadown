package com.yadown.core.transfer

/**
 * 四重 Range 校验，规则见 docs/ARCHITECTURE.md §5.3。
 *
 * 与网络库解耦：调用方把响应快照成 [RangeResponse] 传入，便于纯 JVM 单测。
 */
data class RangeRequest(val start: Long, val endInclusive: Long) {
    val length: Long get() = endInclusive - start + 1
}

data class RangeResponse(
    val statusCode: Int,
    val contentRange: String?,
    val etag: String?,
    val lastModified: String?,
    val bodyLength: Long,
    val totalSize: Long? = null,
    val contentEncoding: String? = null,
    val retryAfterMs: Long? = null
)

data class RangeSession(
    val etag: String?,
    val lastModified: String?,
    val totalSize: Long?
)

sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val cause: FailureCause, val detail: String) : ValidationResult
}

object RangeValidator {

    fun validate(
        response: RangeResponse,
        request: RangeRequest,
        session: RangeSession
    ): ValidationResult {
        if (response.contentEncoding != null && !response.contentEncoding.equals("identity", true)) {
            return invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "Range 响应带 Content-Encoding=${response.contentEncoding}，字节偏移不可信"
            )
        }

        // 1. 状态码：206 才能按区间校验；200 仅当请求覆盖整个文件时允许退化为顺序传输。
        if (response.statusCode == 200) {
            val coversWholeFile = request.start == 0L &&
                (session.totalSize == null || request.endInclusive >= session.totalSize - 1)
            if (!coversWholeFile) {
                return invalid(
                    FailureCause.SERVER_RANGE_UNSUPPORTED,
                    "返回 200 但请求只覆盖部分区间"
                )
            }
            val expected = session.totalSize ?: request.length
            if (response.bodyLength != expected) {
                return invalid(
                    FailureCause.CHECKSUM_MISMATCH,
                    "200 body 长度 ${response.bodyLength} != 预期 $expected"
                )
            }
            return ValidationResult.Valid
        }

        if (response.statusCode == 416) {
            val total = parseUnsatisfiedTotal(response.contentRange)
            return if (session.totalSize != null && total != null && total != session.totalSize) {
                invalid(FailureCause.CONTENT_CHANGED, "416 的 total $total != 会话 ${session.totalSize}")
            } else {
                invalid(FailureCause.RANGE_NOT_SATISFIABLE, "服务器返回 416")
            }
        }

        if (response.statusCode == 429) {
            return invalid(FailureCause.RATE_LIMITED, "服务器限流，retryAfter=${response.retryAfterMs}")
        }

        if (response.statusCode != 206) {
            return invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "非 206 状态码: ${response.statusCode}"
            )
        }

        // 2. Content-Range 必须与请求区间完全一致，且 total 不能漂移。
        val parsed = parseContentRange(response.contentRange)
            ?: return invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "Content-Range 缺失或非法: ${response.contentRange}"
            )
        if (parsed.first != request.start || parsed.second != request.endInclusive) {
            return invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "Content-Range $parsed 与请求 $request 不一致"
            )
        }
        if (session.totalSize != null && parsed.third != null && parsed.third != session.totalSize) {
            return invalid(
                FailureCause.CONTENT_CHANGED,
                "Content-Range total ${parsed.third} != 会话 ${session.totalSize}"
            )
        }

        // 3. 内容不能漂移：优先 ETag，缺 ETag 时用 Last-Modified。
        if (session.etag != null && response.etag != null && !etagMatches(session.etag, response.etag)) {
            return invalid(FailureCause.CONTENT_CHANGED, "ETag 变化")
        }
        if (session.etag == null && session.lastModified != null &&
            response.lastModified != null && session.lastModified != response.lastModified
        ) {
            return invalid(FailureCause.CONTENT_CHANGED, "Last-Modified 变化")
        }

        // 4. body 实际字节数必须与声明区间长度一致，防止截断或空 body。
        if (response.bodyLength != request.length) {
            return invalid(
                FailureCause.CHECKSUM_MISMATCH,
                "body 长度 ${response.bodyLength} != 请求长度 ${request.length}"
            )
        }

        return ValidationResult.Valid
    }

    /**
     * 强 ETag 才能证明字节级一致；弱 ETag 即使 opaque-tag 相同也不能用于 Range 会话。
     */
    private fun etagMatches(session: String, response: String): Boolean {
        if (session == response) return true
        if (session.startsWith("W/") || response.startsWith("W/")) return false
        return session == response
    }

    private fun parseContentRange(value: String?): Triple<Long, Long, Long?>? {
        val match = TRIPLE_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
        val (start, end, total) = match.destructured
        val parsedStart = start.toLongOrNull() ?: return null
        val parsedEnd = end.toLongOrNull() ?: return null
        return Triple(parsedStart, parsedEnd, total.toLongOrNull())
    }

    private fun parseUnsatisfiedTotal(value: String?): Long? {
        val match = UNSATISFIED_RANGE.matchEntire(value?.trim() ?: return null) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    private fun invalid(cause: FailureCause, detail: String) =
        ValidationResult.Invalid(cause, detail)

    private val TRIPLE_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    private val UNSATISFIED_RANGE = Regex("""bytes\s+\*/(\d+)""")
}
