package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossValidatorTest {

    private val chunks = listOf(
        ChunkRange(0, 0, 1023),
        ChunkRange(1, 1024, 2047),
        ChunkRange(2, 2048, 3071)
    )

    @Test
    fun doneWithDataIsNotHollow() {
        val bitmap = ChunkBitmap(3).apply { markDone(0) }
        val hollow = CrossValidator.findHollowChunks(bitmap, chunks) { true }
        assertTrue(hollow.isEmpty())
    }

    @Test
    fun doneWithoutDataIsHollow() {
        val bitmap = ChunkBitmap(3).apply { markDone(0) }
        val hollow = CrossValidator.findHollowChunks(bitmap, chunks) { it.index != 0 }
        assertEquals(listOf(chunks[0]), hollow)
    }

    @Test
    fun pendingWithoutDataIsNotHollow() {
        // 未标记完成的 chunk 本来就没数据，不应算作「DONE 但空洞」。
        val bitmap = ChunkBitmap(3)
        val hollow = CrossValidator.findHollowChunks(bitmap, chunks) { false }
        assertTrue(hollow.isEmpty())
    }

    @Test
    fun multipleHolesAreReportedInOrder() {
        val bitmap = ChunkBitmap(3).apply {
            markDone(0)
            markDone(1)
            markDone(2)
        }
        val hollow = CrossValidator.findHollowChunks(bitmap, chunks) { it.index == 1 }
        assertEquals(listOf(chunks[0], chunks[2]), hollow)
    }
}
