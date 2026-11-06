package com.yadown.core.transfer

/**
 * Stall watchdog：分块在 [timeoutMs] 内没有字节进展即判定卡死。
 * 纯逻辑，不持有线程或协程；调用方在每次收到字节时调 [onProgress]。
 */
class StallWatchdog(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    startAtMs: Long = 0L
) {
    var lastProgressAtMs: Long = startAtMs
        private set

    fun onProgress(nowMs: Long) {
        lastProgressAtMs = nowMs
    }

    fun isStalled(nowMs: Long): Boolean = nowMs - lastProgressAtMs >= timeoutMs

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
