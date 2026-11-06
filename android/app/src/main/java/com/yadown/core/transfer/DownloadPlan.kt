package com.yadown.core.transfer

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
}

fun ChunkRange.length(): Long = endInclusive - start + 1
