package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class ChunkStateMachineTest {

    private fun t(state: ChunkState, event: ChunkEvent, retry: Int = 0): ChunkState =
        ChunkStateMachine.transition(state, event, retry)

    @Test
    fun pending_schedule_becomes_running() {
        assertEquals(ChunkState.RUNNING, t(ChunkState.PENDING, ChunkEvent.Schedule))
    }

    @Test
    fun pending_reset_stays_pending() {
        assertEquals(ChunkState.PENDING, t(ChunkState.PENDING, ChunkEvent.Reset))
    }

    @Test
    fun pending_undefined_event_stays_pending() {
        assertEquals(ChunkState.PENDING, t(ChunkState.PENDING, ChunkEvent.Validated))
    }

    @Test
    fun running_failed_becomes_failed() {
        assertEquals(
            ChunkState.FAILED,
            t(ChunkState.RUNNING, ChunkEvent.Failed(FailureCause.NETWORK_TIMEOUT))
        )
    }

    @Test
    fun running_validated_becomes_done() {
        assertEquals(ChunkState.DONE, t(ChunkState.RUNNING, ChunkEvent.Validated))
    }

    @Test
    fun failed_retry_under_budget_returns_pending() {
        assertEquals(ChunkState.PENDING, t(ChunkState.FAILED, ChunkEvent.Retry, retry = 0))
        assertEquals(ChunkState.PENDING, t(ChunkState.FAILED, ChunkEvent.Retry, retry = 2))
    }

    @Test
    fun failed_retry_at_budget_becomes_invalidated() {
        assertEquals(ChunkState.INVALIDATED, t(ChunkState.FAILED, ChunkEvent.Retry, retry = 3))
        assertEquals(ChunkState.INVALIDATED, t(ChunkState.FAILED, ChunkEvent.Retry, retry = 9))
    }

    @Test
    fun failed_other_event_stays_failed() {
        assertEquals(ChunkState.FAILED, t(ChunkState.FAILED, ChunkEvent.Schedule, retry = 0))
    }

    @Test
    fun invalidated_switch_source_returns_pending() {
        assertEquals(ChunkState.PENDING, t(ChunkState.INVALIDATED, ChunkEvent.SwitchSource))
    }

    @Test
    fun invalidated_other_event_stays_invalidated() {
        assertEquals(ChunkState.INVALIDATED, t(ChunkState.INVALIDATED, ChunkEvent.Retry, retry = 0))
    }

    @Test
    fun done_hole_detected_returns_pending() {
        assertEquals(ChunkState.PENDING, t(ChunkState.DONE, ChunkEvent.HoleDetected()))
    }

    @Test
    fun done_other_event_stays_done() {
        assertEquals(ChunkState.DONE, t(ChunkState.DONE, ChunkEvent.Schedule))
    }
}
