package com.yadown.core.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostTuningPolicyTest {

    private fun sample(limit: Int, throughput: Double, samples: Int = 2) =
        LimitSample(limit, throughput, samples)

    @Test
    fun ewmaFirstSampleIsObserved() {
        assertEquals(1000.0, HostTuningPolicy.ewma(null, 1000.0), 0.0001)
    }

    @Test
    fun ewmaBlendsPreviousAndObserved() {
        assertEquals(1300.0, HostTuningPolicy.ewma(1000.0, 2000.0, 0.3), 0.0001)
    }

    @Test
    fun choosesNearestUnexploredBelowThreshold() {
        val samples = mapOf(8 to sample(8, 2_000_000.0, samples = 2))
        assertEquals(12, HostTuningPolicy.choose(samples, listOf(8, 12, 16), explorationMin = 2))
    }

    @Test
    fun exploresBySampleCountFirst() {
        val samples = mapOf(
            8 to sample(8, 2_000_000.0, samples = 2),
            12 to sample(12, 1_000_000.0, samples = 1)
        )
        assertEquals(16, HostTuningPolicy.choose(samples, listOf(8, 12, 16), explorationMin = 2))
    }

    @Test
    fun picksHighestThroughputWhenAllExplored() {
        val samples = mapOf(
            8 to sample(8, 2_700_000.0),
            12 to sample(12, 1_280_000.0),
            16 to sample(16, 1_370_000.0)
        )
        assertEquals(8, HostTuningPolicy.choose(samples, listOf(8, 12, 16)))
    }

    @Test
    fun emptyCandidatesOrSamplesFallsBack() {
        assertNull(HostTuningPolicy.choose(emptyMap(), emptyList()))
        assertNull(HostTuningPolicy.choose(emptyMap(), listOf(8, 12), explorationMin = 0))
    }

    @Test
    fun hostExtractionLowercasesAndHandlesInvalid() {
        assertEquals("github.com", HostTuning.hostOf("https://GitHub.com/a/b"))
        assertNull(HostTuning.hostOf("not a url"))
    }

    @Test
    fun usableLimitsDropArmsWithTooFewChunksPerConnection() {
        val arms = listOf(8, 12, 16)
        // 23 个分块：每条连接至少要摊 2 个块，只有 8 档合格
        assertEquals(listOf(8), HostTuningPolicy.usableLimits(23, arms))
        // 25 个分块：12 也合格（2.08 块/连接）
        assertEquals(listOf(8, 12), HostTuningPolicy.usableLimits(25, arms))
        // 32 个分块：三档都能摊到 2 个以上
        assertEquals(arms, HostTuningPolicy.usableLimits(32, arms))
    }

    @Test
    fun usableLimitsKeepSmallestArmForTinyFiles() {
        assertEquals(listOf(8), HostTuningPolicy.usableLimits(4, listOf(8, 12, 16)))
    }

    @Test
    fun usableLimitsPassThroughUnknownChunkCount() {
        assertEquals(listOf(8, 12, 16), HostTuningPolicy.usableLimits(0, listOf(8, 12, 16)))
        assertEquals(emptyList<Int>(), HostTuningPolicy.usableLimits(23, emptyList()))
    }
}
