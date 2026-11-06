package com.yadown.core.transfer

import com.yadown.core.db.entity.ChunkEntity

/**
 * 完成谓词：任务完成必须由“字节覆盖 + 文件长度”证明，而不是“待下载队列为空”。
 * 只看 DB 中 DONE 的分块，裂解产生的子块与原始计划块一视同仁。
 */
data class CompletionReport(
    val complete: Boolean,
    val coveredBytes: Long,
    val totalBytes: Long,
    val missing: List<LongRange>
)

object CompletionVerifier {

    fun verify(
        totalSize: Long,
        chunks: List<ChunkEntity>,
        fileLength: Long
    ): CompletionReport {
        if (totalSize <= 0) {
            return CompletionReport(false, 0, totalSize.coerceAtLeast(0), emptyList())
        }

        val downloaded = chunks.asSequence()
            .filter { it.state == ChunkState.DONE.name }
            .map { it.startByte.coerceAtLeast(0)..it.endByte.coerceAtMost(totalSize - 1) }
            .filter { it.first <= it.last }
            .sortedBy { it.first }
            .toList()

        val missing = mutableListOf<LongRange>()
        var cursor = 0L
        var covered = 0L

        for (range in downloaded) {
            if (range.last < cursor) continue
            val start = maxOf(range.first, cursor)
            if (start > cursor) {
                missing += cursor..(start - 1)
            }
            covered += range.last - start + 1
            cursor = range.last + 1
            if (cursor >= totalSize) break
        }

        if (cursor < totalSize) {
            missing += cursor..(totalSize - 1)
        }

        val complete = missing.isEmpty() && fileLength >= totalSize
        return CompletionReport(
            complete = complete,
            coveredBytes = covered.coerceAtMost(totalSize),
            totalBytes = totalSize,
            missing = missing
        )
    }
}
