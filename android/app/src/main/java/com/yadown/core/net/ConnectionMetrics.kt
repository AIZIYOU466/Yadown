package com.yadown.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener

/**
 * 连接复用观测：callStart 与 connectStart 的差额近似为「未新建连接」的调用数。
 * 用于 P2 验收（复用率 > 80%），不参与调度决策。
 */
object ConnectionMetrics {

    data class Snapshot(
        val callStarts: Long,
        val connectStarts: Long,
        val connectionsAcquired: Long
    ) {
        val reuseRate: Double
            get() = if (callStarts == 0L) 0.0 else {
                (callStarts - connectStarts).coerceAtLeast(0L).toDouble() / callStarts
            }
    }

    private val callStarts = AtomicLong()
    private val connectStarts = AtomicLong()
    private val connectionsAcquired = AtomicLong()

    val listener: EventListener = object : EventListener() {
        override fun callStart(call: Call) {
            recordCallStart()
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            recordConnectStart()
        }

        override fun connectionAcquired(call: Call, connection: Connection) {
            recordConnectionAcquired()
        }
    }

    fun recordCallStart() {
        callStarts.incrementAndGet()
    }

    fun recordConnectStart() {
        connectStarts.incrementAndGet()
    }

    fun recordConnectionAcquired() {
        connectionsAcquired.incrementAndGet()
    }

    fun snapshot(): Snapshot = Snapshot(
        callStarts = callStarts.get(),
        connectStarts = connectStarts.get(),
        connectionsAcquired = connectionsAcquired.get()
    )

    fun reset() {
        callStarts.set(0)
        connectStarts.set(0)
        connectionsAcquired.set(0)
    }
}
