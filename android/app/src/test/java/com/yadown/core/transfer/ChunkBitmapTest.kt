package com.yadown.core.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkBitmapTest {

    @Test
    fun markAndRead() {
        val bitmap = ChunkBitmap(3)
        bitmap.markDone(0)
        bitmap.markDone(2)

        assertTrue(bitmap.isDone(0))
        assertFalse(bitmap.isDone(1))
        assertTrue(bitmap.isDone(2))
        assertEquals(2, bitmap.doneCount())
        assertEquals(listOf(1), bitmap.hollowIndices())
        assertFalse(bitmap.allDone())
    }

    @Test
    fun clearMarksPending() {
        val bitmap = ChunkBitmap(2)
        bitmap.markDone(0)
        bitmap.markDone(1)
        assertTrue(bitmap.allDone())

        bitmap.markPending(1)
        assertFalse(bitmap.isDone(1))
        assertEquals(1, bitmap.doneCount())
    }

    @Test
    fun outOfRangeIsNotDone() {
        val bitmap = ChunkBitmap(2)
        assertFalse(bitmap.isDone(-1))
        assertFalse(bitmap.isDone(2))
    }

    @Test
    fun outOfRangeMarkThrows() {
        val bitmap = ChunkBitmap(2)
        try {
            bitmap.markDone(2)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun zeroChunks() {
        val bitmap = ChunkBitmap(0)
        assertEquals(0, bitmap.doneCount())
        assertTrue(bitmap.allDone())
        assertTrue(bitmap.hollowIndices().isEmpty())
    }

    @Test
    fun serializationRoundTrip() {
        val bitmap = ChunkBitmap(130)
        listOf(0, 1, 63, 64, 65, 129).forEach { bitmap.markDone(it) }

        val restored = ChunkBitmap.fromByteArray(bitmap.toByteArray())

        assertEquals(130, restored.totalChunks)
        assertEquals(bitmap.doneCount(), restored.doneCount())
        for (i in 0 until 130) {
            assertEquals("index $i", bitmap.isDone(i), restored.isDone(i))
        }
    }

    @Test
    fun serializationRoundTrip_emptyBitmap() {
        val bitmap = ChunkBitmap(10)
        val restored = ChunkBitmap.fromByteArray(bitmap.toByteArray())
        assertEquals(10, restored.totalChunks)
        assertTrue(restored.hollowIndices().size == 10)
        assertArrayEquals(bitmap.toByteArray(), restored.toByteArray())
    }
}
