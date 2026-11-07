package com.yadown.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkSplitPolicyTest {

    @Test
    fun singleConnectionNeverSplits() {
        assertFalse(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 1_000_000.0,
                blockRate = 1_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 1
            )
        )
    }

    @Test
    fun lowGlobalMedianDisablesSplit() {
        assertFalse(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 40_000.0,
                blockRate = 5_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
    }

    @Test
    fun splitRequiresTransferredUnderQuarter() {
        assertFalse(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 1_000_000.0,
                blockRate = 1_000.0,
                transferredBytes = 300,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
        assertTrue(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 1_000_000.0,
                blockRate = 1_000.0,
                transferredBytes = 200,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
    }

    @Test
    fun blockMustBeClearlySlowerThanMedian() {
        assertFalse(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 1_000_000.0,
                blockRate = 600_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
        assertTrue(
            ChunkSplitPolicy.shouldSplit(
                medianRate = 1_000_000.0,
                blockRate = 400_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
    }

    @Test
    fun coldStartUsesAbsoluteFloor() {
        assertTrue(
            ChunkSplitPolicy.shouldSplit(
                medianRate = null,
                blockRate = 50_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
        assertFalse(
            ChunkSplitPolicy.shouldSplit(
                medianRate = null,
                blockRate = 100_000.0,
                transferredBytes = 10,
                chunkLength = 1_000,
                connectionLimit = 4
            )
        )
    }
}
