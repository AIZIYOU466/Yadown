package com.yadown.core.transfer

import com.yadown.core.db.entity.ChunkEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionVerifierTest {

    private fun chunk(
        start: Long,
        end: Long,
        state: String = ChunkState.DONE.name
    ) = ChunkEntity(
        id = "c-$start-$end",
        intentId = "i1",
        startByte = start,
        endByte = end,
        state = state,
        retryCount = 0,
        checksum = null,
        connectionFingerprint = null,
        failureChainJson = null,
        startedAt = null,
        completedAt = null
    )

    @Test
    fun contiguousDoneChunksAreComplete() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 3), chunk(4, 7), chunk(8, 9)),
            fileLength = 10
        )
        assertTrue(report.complete)
        assertEquals(10, report.coveredBytes)
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun holeInTheMiddleIsNotComplete() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 3), chunk(8, 9)),
            fileLength = 10
        )
        assertFalse(report.complete)
        assertEquals(listOf(4L..7L), report.missing)
        assertEquals(6, report.coveredBytes)
    }

    @Test
    fun invalidatedChunksNeverCountAsCovered() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(
                chunk(0, 3, ChunkState.INVALIDATED.name),
                chunk(4, 9, ChunkState.PENDING.name)
            ),
            fileLength = 10
        )
        assertFalse(report.complete)
        assertEquals(listOf(0L..9L), report.missing)
    }

    @Test
    fun shortFileIsNotCompleteEvenWhenRangesCoverEverything() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 9)),
            fileLength = 9
        )
        assertFalse(report.complete)
        assertTrue(report.missing.isEmpty())
    }

    @Test
    fun splitChildrenBeyondPlanAreCounted() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 4), chunk(5, 9)),
            fileLength = 10
        )
        assertTrue(report.complete)
    }

    @Test
    fun overlappingRangesDoNotDoubleCount() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 6), chunk(4, 9)),
            fileLength = 10
        )
        assertTrue(report.complete)
        assertEquals(10, report.coveredBytes)
    }

    @Test
    fun rangesAreClampedToFileBounds() {
        val report = CompletionVerifier.verify(
            totalSize = 10,
            chunks = listOf(chunk(0, 20)),
            fileLength = 10
        )
        assertTrue(report.complete)
        assertEquals(10, report.coveredBytes)
    }
}
