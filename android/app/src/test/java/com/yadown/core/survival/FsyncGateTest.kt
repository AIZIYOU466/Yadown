package com.yadown.core.survival

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FsyncGateTest {

    @Test
    fun firstCall_shouldFsync() {
        val gate = FsyncGate(2_000)
        assertTrue(gate.shouldFsync(0))
    }

    @Test
    fun withinInterval_shouldNotFsync() {
        val gate = FsyncGate(2_000)
        gate.markFsynced(10_000)
        assertFalse(gate.shouldFsync(11_999))
    }

    @Test
    fun atBoundary_shouldFsync() {
        val gate = FsyncGate(2_000)
        gate.markFsynced(10_000)
        assertTrue(gate.shouldFsync(12_000))
    }

    @Test
    fun afterMark_resetsTimer() {
        val gate = FsyncGate(2_000)
        gate.markFsynced(10_000)
        gate.markFsynced(12_000)
        assertFalse(gate.shouldFsync(12_000))
        assertTrue(gate.shouldFsync(14_000))
    }

    @Test
    fun customInterval() {
        val gate = FsyncGate(500)
        gate.markFsynced(0)
        assertFalse(gate.shouldFsync(499))
        assertTrue(gate.shouldFsync(500))
    }
}