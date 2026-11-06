package com.yadown.core.transfer

import com.yadown.core.semantic.ValueMap

data class DownloadPlan(
    val totalSize: Long,
    val chunkSize: Long,
    val chunks: List<ChunkRange>
) {
    companion object {
        const val DEFAULT_CHUNK_SIZE = 1L * 1024 * 1024

        fun create(totalSize: Long, chunkSize: Long = DEFAULT_CHUNK_SIZE): DownloadPlan {
            require(totalSize > 0) { "totalSize 必须为正: $totalSize" }
            require(chunkSize > 0) { "chunkSize 必须为正: $chunkSize" }

            val chunks = ArrayList<ChunkRange>()
            var start = 0L
            var index = 0
            while (start < totalSize) {
                val end = minOf(start + chunkSize - 1, totalSize - 1)
                chunks += ChunkRange(index++, start, end)
                start = end + 1
            }
            return DownloadPlan(totalSize, chunkSize, chunks)
        }
    }

    /**
     * 语义优先下发顺序：权重高的分块排前面。
     *
     * 只改变排队次序，不改分块边界与 index —— 位图按 index 索引，
     * 一旦重排边界就会让已落盘的位图与文件内容错位。
     * 权重相同时保持原有 index 升序，保证顺序传输场景（valueMap 为空）行为与之前完全一致。
     */
    fun priorityOrder(valueMap: ValueMap?): List<Int> {
        if (valueMap == null) return chunks.indices.toList()
        return chunks.indices.sortedWith(
            compareByDescending<Int> { valueMap.weightAt(chunks[it].start) }
                .thenBy { it }
        )
    }
}

fun ChunkRange.length(): Long = endInclusive - start + 1
