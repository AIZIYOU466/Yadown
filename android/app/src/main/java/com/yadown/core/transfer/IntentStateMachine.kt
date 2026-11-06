package com.yadown.core.transfer

/** Intent 状态机，转换规则见 docs/ARCHITECTURE.md §3.2。 */
enum class IntentState { PENDING, ACTIVE, PAUSED, COMPLETED, FAILED }

sealed interface IntentEvent {
    data object Start : IntentEvent
    data object Pause : IntentEvent
    data object Resume : IntentEvent
    data object Complete : IntentEvent
    data class Fail(val cause: FailureCause) : IntentEvent
    data object UserRetry : IntentEvent
}

object IntentStateMachine {
    fun transition(current: IntentState, event: IntentEvent): IntentState =
        when (current) {
            IntentState.PENDING -> when (event) {
                IntentEvent.Start -> IntentState.ACTIVE
                else -> current
            }

            IntentState.ACTIVE -> when (event) {
                IntentEvent.Pause -> IntentState.PAUSED
                IntentEvent.Complete -> IntentState.COMPLETED
                is IntentEvent.Fail -> IntentState.FAILED
                else -> current
            }

            IntentState.PAUSED -> when (event) {
                IntentEvent.Resume -> IntentState.ACTIVE
                else -> current
            }

            IntentState.COMPLETED -> current

            IntentState.FAILED -> when (event) {
                IntentEvent.UserRetry -> IntentState.PENDING
                else -> current
            }
        }
}
