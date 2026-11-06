package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class HealthTrackerTest {

    @Test
    fun initialScore_isOne() {
        assertEquals(1f, HealthTracker().score(), 0.001f)
    }

    @Test
    fun allSuccess_keepsOne() {
        val tracker = HealthTracker()
        repeat(5) { tracker.recordSuccess() }
        assertEquals(1f, tracker.score(), 0.001f)
    }

    @Test
    fun halfFailure_scoresHalf() {
        val tracker = HealthTracker()
        tracker.recordSuccess()
        tracker.recordFailure()
        assertEquals(0.5f, tracker.score(), 0.001f)
    }

    @Test
    fun allFailure_scoresZero() {
        val tracker = HealthTracker()
        repeat(3) { tracker.recordFailure() }
        assertEquals(0f, tracker.score(), 0.001f)
    }
}