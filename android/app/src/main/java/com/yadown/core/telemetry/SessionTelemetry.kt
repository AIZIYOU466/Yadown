package com.yadown.core.telemetry

import java.util.concurrent.ConcurrentHashMap

/**
 * 按 intent 的会话遥测：区分「本次任务」与进程累计，避免用历史数字判断当前任务。
 * 进程累计仍由 [TransferTelemetry] 维护，两者在诊断页分栏展示。
 */
object SessionTelemetry {

    data class Session(
        val intentId: String,
        val startedAt: Long,
        var chunksDone: Long = 0,
        var chunksFailed: Long = 0,
        var bytesDone: Long = 0,
        var retries: Long = 0,
        var stalls: Long = 0,
        var slowSplits: Long = 0,
        var finishedAt: Long? = null
    )

    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var lastFinished: Session? = null

    fun begin(intentId: String) {
        synchronized(lock) {
            sessions[intentId] = Session(intentId, System.currentTimeMillis())
        }
    }

    fun onChunkDone(intentId: String, bytes: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { session ->
                session.chunksDone++
                session.bytesDone += bytes.coerceAtLeast(0)
            }
        }
    }

    fun onChunkFailed(intentId: String) {
        synchronized(lock) {
            sessions[intentId]?.let { it.chunksFailed++ }
        }
    }

    fun onRetry(intentId: String) {
        synchronized(lock) {
            sessions[intentId]?.let { it.retries++ }
        }
    }

    fun onStall(intentId: String) {
        synchronized(lock) {
            sessions[intentId]?.let { it.stalls++ }
        }
    }

    fun onSlowSplit(intentId: String) {
        synchronized(lock) {
            sessions[intentId]?.let { it.slowSplits++ }
        }
    }

    fun finish(intentId: String): Session? {
        synchronized(lock) {
            val session = sessions.remove(intentId) ?: return null
            session.finishedAt = System.currentTimeMillis()
            lastFinished = session
            return session
        }
    }

    fun current(intentId: String): Session? = sessions[intentId]

    /** 最近一次登记或结束的会话，供诊断页展示。 */
    fun latest(): Session? {
        val active = sessions.values.maxByOrNull { it.startedAt }
        if (active != null) return active
        return lastFinished
    }

    fun reset() {
        synchronized(lock) {
            sessions.clear()
            lastFinished = null
        }
    }
}
