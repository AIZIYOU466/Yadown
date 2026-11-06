package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PlanFingerprintTest {

    @Test
    fun samePlanProducesSameFingerprint() {
        assertEquals(
            PlanFingerprint.of(100, 10),
            PlanFingerprint.of(100, 10)
        )
    }

    @Test
    fun planChangeInvalidatesFingerprint() {
        assertNotEquals(
            PlanFingerprint.of(100, 10),
            PlanFingerprint.of(100, 20)
        )
        assertNotEquals(
            PlanFingerprint.of(100, 10),
            PlanFingerprint.of(200, 10)
        )
        assertNotEquals(
            PlanFingerprint.of(100, 10, planVersion = 1),
            PlanFingerprint.of(100, 10, planVersion = 2)
        )
    }

    @Test
    fun fingerprintIsStableHex() {
        val value = PlanFingerprint.of(1024, 1024)
        assertEquals(64, value.length)
        assertEquals(value.lowercase(), value)
    }
}
