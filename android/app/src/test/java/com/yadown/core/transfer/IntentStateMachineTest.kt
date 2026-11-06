package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class IntentStateMachineTest {

    private fun t(state: IntentState, event: IntentEvent): IntentState =
        IntentStateMachine.transition(state, event)

    @Test
    fun pending_start_becomes_active() {
        assertEquals(IntentState.ACTIVE, t(IntentState.PENDING, IntentEvent.Start))
    }

    @Test
    fun pending_other_stays_pending() {
        assertEquals(IntentState.PENDING, t(IntentState.PENDING, IntentEvent.Complete))
    }

    @Test
    fun active_pause_becomes_paused() {
        assertEquals(IntentState.PAUSED, t(IntentState.ACTIVE, IntentEvent.Pause))
    }

    @Test
    fun active_complete_becomes_completed() {
        assertEquals(IntentState.COMPLETED, t(IntentState.ACTIVE, IntentEvent.Complete))
    }

    @Test
    fun active_fail_becomes_failed() {
        assertEquals(
            IntentState.FAILED,
            t(IntentState.ACTIVE, IntentEvent.Fail(FailureCause.STORAGE_FULL))
        )
    }

    @Test
    fun paused_resume_becomes_active() {
        assertEquals(IntentState.ACTIVE, t(IntentState.PAUSED, IntentEvent.Resume))
    }

    @Test
    fun paused_other_stays_paused() {
        assertEquals(IntentState.PAUSED, t(IntentState.PAUSED, IntentEvent.Complete))
    }

    @Test
    fun completed_ignores_events() {
        assertEquals(IntentState.COMPLETED, t(IntentState.COMPLETED, IntentEvent.Start))
        assertEquals(IntentState.COMPLETED, t(IntentState.COMPLETED, IntentEvent.Pause))
    }

    @Test
    fun failed_user_retry_returns_pending() {
        assertEquals(IntentState.PENDING, t(IntentState.FAILED, IntentEvent.UserRetry))
    }

    @Test
    fun failed_other_stays_failed() {
        assertEquals(IntentState.FAILED, t(IntentState.FAILED, IntentEvent.Start))
    }
}
