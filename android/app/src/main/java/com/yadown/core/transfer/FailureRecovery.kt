package com.yadown.core.transfer

/** 由失败归因链首因决定的恢复动作，见 docs/ARCHITECTURE.md §4 恢复决策表。 */
enum class RecoveryAction {
    RETRY_BACKOFF,
    PAUSE_WAIT_NETWORK,
    REDUCE_CONCURRENCY_SWITCH_SOURCE,
    INVALIDATE_ALL_RESTART,
    PAUSE_WAIT_STORAGE,
    PAUSE_NOTIFY_USER,
    REGRANT_PERMISSIONS,
    SEQUENTIAL_FALLBACK,
    NONE
}

object FailureRecovery {
    fun action(chain: FailureChain): RecoveryAction =
        when (chain.primary) {
            FailureCause.NETWORK_TIMEOUT,
            FailureCause.DNS_RESOLVE_FAILED -> RecoveryAction.RETRY_BACKOFF

            FailureCause.PRIVATE_APN_DETECTED -> RecoveryAction.PAUSE_WAIT_NETWORK

            FailureCause.SERVER_SILENT_REJECTION -> RecoveryAction.REDUCE_CONCURRENCY_SWITCH_SOURCE

            FailureCause.CONTENT_CHANGED -> RecoveryAction.INVALIDATE_ALL_RESTART

            FailureCause.STORAGE_UNREACHABLE -> RecoveryAction.PAUSE_WAIT_STORAGE

            FailureCause.STORAGE_FULL -> RecoveryAction.PAUSE_NOTIFY_USER

            FailureCause.ROM_KILLED_SERVICE -> RecoveryAction.REGRANT_PERMISSIONS

            // 文档恢复表未显式列出的归因，按语义补全（扩展表见 ARCHITECTURE §4）。
            FailureCause.TLS_HANDSHAKE_FAILED -> RecoveryAction.RETRY_BACKOFF
            FailureCause.SERVER_RANGE_UNSUPPORTED -> RecoveryAction.SEQUENTIAL_FALLBACK
            FailureCause.RANGE_NOT_SATISFIABLE -> RecoveryAction.RETRY_BACKOFF
            FailureCause.CHECKSUM_MISMATCH -> RecoveryAction.RETRY_BACKOFF
            FailureCause.RATE_LIMITED -> RecoveryAction.RETRY_BACKOFF
            FailureCause.LINK_EXPIRED -> RecoveryAction.PAUSE_NOTIFY_USER
            FailureCause.BUDGET_EXCEEDED -> RecoveryAction.PAUSE_NOTIFY_USER
            FailureCause.PEER_UNAVAILABLE -> RecoveryAction.REDUCE_CONCURRENCY_SWITCH_SOURCE
            FailureCause.INCOMPLETE -> RecoveryAction.NONE
            FailureCause.UNKNOWN -> RecoveryAction.RETRY_BACKOFF

            null -> RecoveryAction.NONE
        }
}
