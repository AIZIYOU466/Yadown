package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureRecoveryTest {

    private fun action(vararg causes: FailureCause): RecoveryAction =
        FailureRecovery.action(FailureChain(causes.toList()))

    @Test
    fun networkTimeout_retryBackoff() {
        assertEquals(RecoveryAction.RETRY_BACKOFF, action(FailureCause.NETWORK_TIMEOUT))
    }

    @Test
    fun dnsResolveFailed_retryBackoff() {
        assertEquals(RecoveryAction.RETRY_BACKOFF, action(FailureCause.DNS_RESOLVE_FAILED))
    }

    @Test
    fun privateApn_pauseWaitNetwork() {
        assertEquals(RecoveryAction.PAUSE_WAIT_NETWORK, action(FailureCause.PRIVATE_APN_DETECTED))
    }

    @Test
    fun serverSilentRejection_reduceConcurrencySwitchSource() {
        assertEquals(
            RecoveryAction.REDUCE_CONCURRENCY_SWITCH_SOURCE,
            action(FailureCause.SERVER_SILENT_REJECTION)
        )
    }

    @Test
    fun contentChanged_invalidateAllRestart() {
        assertEquals(RecoveryAction.INVALIDATE_ALL_RESTART, action(FailureCause.CONTENT_CHANGED))
    }

    @Test
    fun storageUnreachable_pauseWaitStorage() {
        assertEquals(RecoveryAction.PAUSE_WAIT_STORAGE, action(FailureCause.STORAGE_UNREACHABLE))
    }

    @Test
    fun storageFull_pauseNotifyUser() {
        assertEquals(RecoveryAction.PAUSE_NOTIFY_USER, action(FailureCause.STORAGE_FULL))
    }

    @Test
    fun romKilledService_regrantPermissions() {
        assertEquals(RecoveryAction.REGRANT_PERMISSIONS, action(FailureCause.ROM_KILLED_SERVICE))
    }

    @Test
    fun primaryCauseDrivesDecision() {
        assertEquals(
            RecoveryAction.INVALIDATE_ALL_RESTART,
            action(FailureCause.CONTENT_CHANGED, FailureCause.NETWORK_TIMEOUT)
        )
    }

    @Test
    fun emptyChain_none() {
        assertEquals(RecoveryAction.NONE, FailureRecovery.action(FailureChain(emptyList())))
    }

    @Test
    fun extendedChecksumMismatch_retryBackoff() {
        assertEquals(RecoveryAction.RETRY_BACKOFF, action(FailureCause.CHECKSUM_MISMATCH))
    }

    @Test
    fun extendedPeerUnavailable_reduceConcurrencySwitchSource() {
        assertEquals(
            RecoveryAction.REDUCE_CONCURRENCY_SWITCH_SOURCE,
            action(FailureCause.PEER_UNAVAILABLE)
        )
    }

    @Test
    fun extendedUnknown_retryBackoff() {
        assertEquals(RecoveryAction.RETRY_BACKOFF, action(FailureCause.UNKNOWN))
    }
}
