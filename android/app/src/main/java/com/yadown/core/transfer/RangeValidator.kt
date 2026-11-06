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
    val bodyLength: Long
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
        // 1. 状态码：206 才能按区间校验；200 仅当请求覆盖整个文件时允许退化为顺序传输。
        if (response.statusCode == 200) {
            val coversWholeFile = request.start == 0L &&
                (session.totalSize == null || request.endInclusive >= session.totalSize - 1)
            return if (coversWholeFile) {
                ValidationResult.Valid
            } else {
                ValidationResult.Invalid(
                    FailureCause.SERVER_RANGE_UNSUPPORTED,
                    "返回 200 但请求只覆盖部分区间"
                )
            }
        }
        if (response.statusCode != 206) {
            return ValidationResult.Invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "非 206 状态码: ${response.statusCode}"
            )
        }

        // 2. Content-Range 必须与请求区间完全一致。
        val parsed = parseContentRange(response.contentRange)
            ?: return ValidationResult.Invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "Content-Range 缺失或非法: ${response.contentRange}"
            )
        if (parsed.first != request.start || parsed.second != request.endInclusive) {
            return ValidationResult.Invalid(
                FailureCause.SERVER_SILENT_REJECTION,
                "Content-Range $parsed 与请求 $request 不一致"
            )
        }

        // 3. 内容不能漂移：优先 ETag，缺 ETag 时用 Last-Modified。
        if (session.etag != null && response.etag != null && session.etag != response.etag) {
            return ValidationResult.Invalid(FailureCause.CONTENT_CHANGED, "ETag 变化")
        }
        if (session.etag == null && session.lastModified != null &&
            response.lastModified != null && session.lastModified != response.lastModified
        ) {
            return ValidationResult.Invalid(FailureCause.CONTENT_CHANGED, "Last-Modified 变化")
        }

        // 4. body 实际字节数必须与声明区间长度一致，防止截断或空 body。
        if (response.bodyLength != request.length) {
            return ValidationResult.Invalid(
                FailureCause.CHECKSUM_MISMATCH,
                "body 长度 ${response.bodyLength} != 请求长度 ${request.length}"
            )
        }

        return ValidationResult.Valid
    }

    private fun parseContentRange(value: String?): Pair<Long, Long>? {
        val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
            .matchEntire(value?.trim() ?: return null) ?: return null
        val (start, end) = match.destructured
        return start.toLongOrNull()?.let { s ->
            end.toLongOrNull()?.let { e -> s to e }
        }
    }
}
