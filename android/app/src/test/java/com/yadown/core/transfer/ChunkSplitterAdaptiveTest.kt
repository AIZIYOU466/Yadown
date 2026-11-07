package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ChunkSplitterAdaptiveTest {

    private val megabyte = 1024L * 1024

    @Test
    fun lowRateSplitsOneMegabyteChunkIntoTwo() {
        val split = ChunkSplitter.splitAdaptive(0, megabyte - 1, rateBytesPerSec = 200_000.0)

        assertNotNull(split)
        assertEquals(0L, split!!.first.first)
        assertEquals(megabyte / 2 - 1, split.first.last)
        assertEquals(megabyte / 2, split.second.first)
        assertEquals(megabyte - 1, split.second.last)
    }

    @Test
    fun lowRateStopsAtHalfMegabyte() {
        assertNull(ChunkSplitter.splitAdaptive(0, 512L * 1024 - 1, rateBytesPerSec = 100_000.0))
    }

    @Test
    fun highRateDoesNotSplitSmallChunks() {
        assertNull(ChunkSplitter.splitAdaptive(0, megabyte - 1, rateBytesPerSec = 5_000_000.0))
        assertNotNull(
            ChunkSplitter.splitAdaptive(0, 4 * megabyte - 1, rateBytesPerSec = 5_000_000.0)
        )
    }

    @Test
    fun tailChunkIsSplitDownToSixtyFourKilobytes() {
        val tail = 100L * 1024 - 1
        val split = ChunkSplitter.splitAdaptive(0, tail, rateBytesPerSec = 10_000.0)
        assertNotNull(split)

        val small = ChunkSplitter.splitAdaptive(0, 64L * 1024 - 1, rateBytesPerSec = 10_000.0)
        assertNull(small)
    }

    @Test
    fun singleByteChunkIsNotSplittable() {
        assertNull(ChunkSplitter.splitAdaptive(0, 0, rateBytesPerSec = 1.0))
    }
}
