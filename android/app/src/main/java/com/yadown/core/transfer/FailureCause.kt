package com.yadown.core.transfer

/** 失败归因分类，与 docs/ARCHITECTURE.md §4 一一对应。 */
enum class FailureCause {
    NETWORK_TIMEOUT,
    DNS_RESOLVE_FAILED,
    TLS_HANDSHAKE_FAILED,
    PRIVATE_APN_DETECTED,
    SERVER_RANGE_UNSUPPORTED,
    RANGE_NOT_SATISFIABLE,
    SERVER_SILENT_REJECTION,
    CONTENT_CHANGED,
    CHECKSUM_MISMATCH,
    STORAGE_UNREACHABLE,
    STORAGE_FULL,
    ROM_KILLED_SERVICE,
    RATE_LIMITED,
    /** 401/403/签名过期：链接本身失效，不归因于源站质量。 */
    LINK_EXPIRED,
    BUDGET_EXCEEDED,
    PEER_UNAVAILABLE,
    INCOMPLETE,
    UNKNOWN
}

/**
 * 一次失败的完整归因链，按发生顺序记录多个原因，而不是只留最后一个。
 * 恢复决策由链首原因驱动（见 [FailureRecovery]）。
 */
data class FailureChain(
    val causes: List<FailureCause>,
    val context: Map<String, String> = emptyMap()
) {
    val primary: FailureCause? get() = causes.firstOrNull()

    fun append(cause: FailureCause): FailureChain = copy(causes = causes + cause)
}
