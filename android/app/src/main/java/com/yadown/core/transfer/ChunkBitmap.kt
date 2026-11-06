package com.yadown.core.transfer

import java.nio.ByteBuffer

/**
 * 分块完成位图。用 LongArray 做紧凑存储，支持序列化为位图侧车文件。
 * 位图只表达「哪些分块已完成」，磁盘空洞由 [CrossValidator] 交叉校验发现。
 */
class ChunkBitmap(val totalChunks: Int) {

    init {
        require(totalChunks >= 0) { "totalChunks 不能为负: $totalChunks" }
    }

    private val words = LongArray((totalChunks + 63) / 64)

    fun markDone(index: Int) {
        require(index in 0 until totalChunks) { "index $index 超出 0..${totalChunks - 1}" }
        words[index ushr 6] = words[index ushr 6] or bit(index)
    }

    fun markPending(index: Int) {
        require(index in 0 until totalChunks) { "index $index 超出 0..${totalChunks - 1}" }
        words[index ushr 6] = words[index ushr 6] and bit(index).inv()
    }

    fun isDone(index: Int): Boolean {
        if (index !in 0 until totalChunks) return false
        return words[index ushr 6] and bit(index) != 0L
    }

    fun doneCount(): Int = words.sumOf { it.countOneBits() }

    fun allDone(): Boolean = doneCount() == totalChunks

    fun hollowIndices(): List<Int> = (0 until totalChunks).filterNot(::isDone)

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + words.size * 8)
        buffer.putInt(totalChunks)
        words.forEach { buffer.putLong(it) }
        return buffer.array()
    }

    private fun bit(index: Int): Long = 1L shl (index and 63)

    companion object {
        fun fromByteArray(bytes: ByteArray): ChunkBitmap {
            val buffer = ByteBuffer.wrap(bytes)
            val total = buffer.int
            val bitmap = ChunkBitmap(total)
            for (i in bitmap.words.indices) {
                if (!buffer.hasRemaining()) break
                bitmap.words[i] = buffer.long
            }
            return bitmap
        }
    }
}

data class ChunkRange(val index: Int, val start: Long, val endInclusive: Long)

object CrossValidator {
    /**
     * 找出位图标记为 DONE、但文件对应区间实际没有数据的 chunk（空洞）。
     * hasData 由调用方提供，便于把文件读取与纯逻辑解耦并单测。
     */
    fun findHollowChunks(
        bitmap: ChunkBitmap,
        chunks: List<ChunkRange>,
        hasData: (ChunkRange) -> Boolean
    ): List<ChunkRange> = chunks.filter { chunk ->
        bitmap.isDone(chunk.index) && !hasData(chunk)
    }
}
