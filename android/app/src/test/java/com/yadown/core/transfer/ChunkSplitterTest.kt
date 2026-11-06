package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkSplitterTest {

    @Test
    fun evenLengthSplitsInHalf() {
        val (a, b) = ChunkSplitter.split(0, 1023)!!
        assertEquals(0L..511L, a)
        assertEquals(512L..1023L, b)
    }

    @Test
    fun oddLengthSplitsAlmostInHalf() {
        val (a, b) = ChunkSplitter.split(0, 999)!!
        assertEquals(0L..499L, a)
        assertEquals(500L..999L, b)
    }

    @Test
    fun twoBytesSplitIntoTwoSingletons() {
        val (a, b) = ChunkSplitter.split(5, 6)!!
        assertEquals(5L..5L, a)
        assertEquals(6L..6L, b)
    }

    @Test
    fun singleByteCannotSplit() {
        assertNull(ChunkSplitter.split(10, 10))
    }

    @Test
    fun zeroLengthCannotSplit() {
        assertNull(ChunkSplitter.split(10, 9))
    }

    @Test
    fun splitCoversOriginalRangeWithoutGap() {
        val original = 100L..1099L
        val (a, b) = ChunkSplitter.split(original.first, original.last)!!
        assertEquals(original.first, a.first)
        assertEquals(original.last, b.last)
        assertEquals(a.last + 1, b.first)
    }
}
