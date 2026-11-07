package com.yadown.core.telemetry

import com.yadown.core.transfer.FailureCause
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 全层共享的运行时可观测数据。当前是进程内聚合，
 * 由 UI/调试入口读取；持久化落库是下一步，不把“写了指标类”当成已完成。
 */
object TransferTelemetry {

    data class Snapshot(
        val chunksDone: Long,
        val chunksFailed: Long,
        val stalls: Long,
        val slowSplits: Long,
        val bytesDone: Long,
        val firstPlayableSamples: Long,
        val firstPlayableTotalMs: Long,
        val failureByCause: Map<FailureCause, Long>
    ) {
        val firstPlayableAvgMs: Long
            get() = if (firstPlayableSamples == 0L) 0L else firstPlayableTotalMs / firstPlayableSamples

        val stallRate: Double
            get() {
                val events = chunksDone + chunksFailed + stalls
                return if (events == 0L) 0.0 else stalls.toDouble() / events
            }
    }

    private val chunksDone = AtomicLong()
    private val chunksFailed = AtomicLong()
    private val stalls = AtomicLong()
    private val slowSplits = AtomicLong()
    private val bytesDone = AtomicLong()
    private val firstPlayableSamples = AtomicLong()
    private val firstPlayableTotalMs = AtomicLong()
    private val failures = ConcurrentHashMap<FailureCause, AtomicLong>()

    @Volatile
    private var store: TelemetryStore? = null

    fun installStore(store: TelemetryStore) {
        this.store = store
    }

    fun onChunkDone(bytes: Long) {
        chunksDone.incrementAndGet()
        bytesDone.addAndGet(bytes.coerceAtLeast(0))
        store?.append("chunk_done,$bytes")
    }

    fun onChunkFailed(cause: FailureCause) {
        chunksFailed.incrementAndGet()
        failures.computeIfAbsent(cause) { AtomicLong() }.incrementAndGet()
        store?.append("chunk_failed,${cause.name}")
    }

    fun onStall() {
        stalls.incrementAndGet()
        store?.append("stall")
    }

    fun onSlowSplit() {
        slowSplits.incrementAndGet()
        store?.append("slow_split")
    }

    fun onAvailable(elapsedMs: Long) {
        firstPlayableSamples.incrementAndGet()
        firstPlayableTotalMs.addAndGet(elapsedMs.coerceAtLeast(0))
        store?.append("available,$elapsedMs")
    }

    fun snapshot(): Snapshot = Snapshot(
        chunksDone = chunksDone.get(),
        chunksFailed = chunksFailed.get(),
        stalls = stalls.get(),
        slowSplits = slowSplits.get(),
        bytesDone = bytesDone.get(),
        firstPlayableSamples = firstPlayableSamples.get(),
        firstPlayableTotalMs = firstPlayableTotalMs.get(),
        failureByCause = failures.mapValues { it.value.get() }
    )

    fun reset() {
        chunksDone.set(0)
        chunksFailed.set(0)
        stalls.set(0)
        slowSplits.set(0)
        bytesDone.set(0)
        firstPlayableSamples.set(0)
        firstPlayableTotalMs.set(0)
        failures.clear()
    }
}
