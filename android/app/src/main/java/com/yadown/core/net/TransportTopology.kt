package com.yadown.core.net

import android.content.Context

/**
 * 单链路传输拓扑：决定并发分块请求在一条链路上如何占用 TCP 连接。
 *
 * 背景（ARCHITECTURE §5.3 拓扑观察）：OkHttp 在 TLS 上默认协商 HTTP/2，
 * 8–16 个并发 Range 请求会被复用到同一条 TCP 的多个流上，聚合吞吐等于单 TCP 吞吐
 * （受 cwnd/RTT 与丢包后的全局回退限制）。两种模式用于 A/B 判定这一假设：
 *
 * - [REUSE]：默认，保留 HTTP/2，单连接复用；
 * - [MULTI_CONNECTION]：强制 HTTP/1.1，每个并发分块各占一条 TCP（浏览器每主机多连接的等价做法）。
 *
 * 除底层 [RangeSource] 外，两种模式共用同一套调度、四重校验与提交协议。
 */
enum class TransportTopology {
    REUSE,
    MULTI_CONNECTION
}

/** 拓扑选择持久化。默认 [TransportTopology.REUSE]，保证未做真机 A/B 前行为不变。 */
class TransportTopologySettings(context: Context) {

    private val prefs = context.getSharedPreferences("transport_topology", Context.MODE_PRIVATE)

    var topology: TransportTopology
        get() = prefs.getString(KEY_TOPOLOGY, null)
            ?.let { name -> TransportTopology.entries.firstOrNull { it.name == name } }
            ?: TransportTopology.REUSE
        set(value) {
            prefs.edit().putString(KEY_TOPOLOGY, value.name).apply()
        }

    private companion object {
        const val KEY_TOPOLOGY = "topology"
    }
}

/**
 * 按当前设置把请求交给对应拓扑的实现，其余调用方（引擎/播放器/peer）无感知。
 *
 * - 每次请求重新读取设置：分块粒度 1MB，读取成本可忽略，用户切换后下一个分块即生效；
 * - 多连接实现延迟创建：未启用时不会多出一个客户端、dispatcher 与连接池。
 */
class TopologyRangeSource(
    private val reuse: RangeSource,
    private val multiConnection: () -> RangeSource,
    private val topology: () -> TransportTopology
) : RangeSource {

    private val multi by lazy(multiConnection)

    override suspend fun probe(url: String, linkId: String?): ProbeResult =
        current().probe(url, linkId)

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload = current().readRange(url, start, endInclusive, onProgress, linkId)

    override suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload =
        current().readRangeChecked(url, start, endInclusive, totalSize, onProgress, linkId)

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = current().readSequential(url, start, onProgress, onBytes)

    private fun current(): RangeSource = when (topology()) {
        TransportTopology.REUSE -> reuse
        TransportTopology.MULTI_CONNECTION -> multi
    }
}
