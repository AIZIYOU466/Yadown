package com.yadown.core.telemetry

import com.yadown.core.net.ConnectionMetrics
import java.util.concurrent.ConcurrentHashMap

/**
 * 按 intent 的会话遥测：区分「本次任务」与进程累计，避免用历史数字判断当前任务。
 * 进程累计仍由 [TransferTelemetry] 维护，两者在诊断页分栏展示。
 */
object SessionTelemetry {

    data class Session(
        val intentId: String,
        val startedAt: Long,
        /** 会话开始时的传输拓扑（REUSE / MULTI_CONNECTION），用于 A/B 对比时区分两组数据。 */
        val topology: String = "",
        /** 本会话允许的并发预算（MULTI 下即 TCP 连接数上限）。 */
        val connectionBudget: Int = 0,
        var chunksDone: Long = 0,
        var chunksFailed: Long = 0,
        var bytesDone: Long = 0,
        var retries: Long = 0,
        var stalls: Long = 0,
        var slowSplits: Long = 0,
        var peakConcurrentRequests: Int = 0,
        var connectStarts: Long = 0,
        var connectBaseline: Long = 0,
        var callStarts: Long = 0,
        var callBaseline: Long = 0,
        /** 首块完成距会话开始的毫秒数（起步延迟）。 */
        var firstChunkAtMs: Long? = null,
        /** 首个分块请求真正发出（取得传输许可）距会话开始的毫秒数：不含调度与预热。 */
        var firstRequestAtMs: Long? = null,
        /** 最后一个分块完成距会话开始的毫秒数，与 [firstRequestAtMs] 构成传输窗口。 */
        var transferFinishedAtMs: Long? = null,
        /** 结束前 flush + 最终提交（fsync）耗时。 */
        var flushMs: Long? = null,
        /** 完成校验耗时。 */
        var verifyMs: Long? = null,
        var finishedAt: Long? = null
    ) {
        val elapsedMs: Long
            get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt).coerceAtLeast(1)

        val avgBytesPerSec: Double
            get() = bytesDone * 1000.0 / elapsedMs

        /** 传输窗口内的吞吐：排除起步与结束提交开销，用于跨连接档位比较。 */
        val steadyStateBytesPerSec: Double
            get() {
                val start = firstRequestAtMs ?: return avgBytesPerSec
                val end = transferFinishedAtMs ?: return avgBytesPerSec
                val window = (end - start).coerceAtLeast(1)
                return bytesDone * 1000.0 / window
            }

        /** 本会话数据请求的复用率（不含会话前的探测与建任务开销）。 */
        val reuseRate: Double
            get() = if (callStarts == 0L) 0.0 else {
                (callStarts - connectStarts).coerceAtLeast(0L).toDouble() / callStarts
            }
    }

    private val lock = Any()
    private val sessions = ConcurrentHashMap<String, Session>()

    @Volatile
    private var lastFinished: Session? = null

    fun begin(intentId: String, topology: String = "", connectionBudget: Int = 0) {
        synchronized(lock) {
            sessions[intentId] = Session(
                intentId = intentId,
                startedAt = System.currentTimeMillis(),
                topology = topology,
                connectionBudget = connectionBudget,
                connectBaseline = ConnectionMetrics.snapshot().connectStarts,
                callBaseline = ConnectionMetrics.snapshot().callStarts
            )
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

    fun onPeakConcurrency(intentId: String, concurrent: Int) {
        synchronized(lock) {
            sessions[intentId]?.let { session ->
                if (concurrent > session.peakConcurrentRequests) {
                    session.peakConcurrentRequests = concurrent
                }
            }
        }
    }

    fun onFirstChunk(intentId: String, elapsedMs: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { session ->
                if (session.firstChunkAtMs == null) session.firstChunkAtMs = elapsedMs.coerceAtLeast(0)
            }
        }
    }

    fun onFirstRequest(intentId: String, elapsedMs: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { session ->
                if (session.firstRequestAtMs == null) {
                    session.firstRequestAtMs = elapsedMs.coerceAtLeast(0)
                }
            }
        }
    }

    fun onTransferFinished(intentId: String, elapsedMs: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { it.transferFinishedAtMs = elapsedMs.coerceAtLeast(0) }
        }
    }

    fun onFlush(intentId: String, elapsedMs: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { it.flushMs = elapsedMs.coerceAtLeast(0) }
        }
    }

    fun onVerify(intentId: String, elapsedMs: Long) {
        synchronized(lock) {
            sessions[intentId]?.let { it.verifyMs = elapsedMs.coerceAtLeast(0) }
        }
    }

    fun finish(intentId: String): Session? {
        synchronized(lock) {
            val session = sessions.remove(intentId) ?: return null
            session.finishedAt = System.currentTimeMillis()
            session.connectStarts = (
                ConnectionMetrics.snapshot().connectStarts - session.connectBaseline
                ).coerceAtLeast(0)
            session.callStarts = (
                ConnectionMetrics.snapshot().callStarts - session.callBaseline
                ).coerceAtLeast(0)
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
