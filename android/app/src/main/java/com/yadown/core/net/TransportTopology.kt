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
    /** 按文件大小自动选择：大文件多连接、小文件单连接。 */
    AUTO,
    REUSE,
    MULTI_CONNECTION
}

/** 拓扑选择持久化。默认 [TransportTopology.AUTO]：大文件走多连接，小文件保持单连接。 */
class TransportTopologySettings(context: Context) {

    private val prefs = context.getSharedPreferences("transport_topology", Context.MODE_PRIVATE)

    var topology: TransportTopology
        get() = prefs.getString(KEY_TOPOLOGY, null)
            ?.let { name -> TransportTopology.entries.firstOrNull { it.name == name } }
            ?: TransportTopology.AUTO
        set(value) {
            prefs.edit().putString(KEY_TOPOLOGY, value.name).apply()
        }

    /** MULTI_CONNECTION 下的 TCP 并发数；用于找出握手成本与叠加吞吐的交叉点。 */
    var multiConnectionLimit: Int
        get() = prefs.getInt(KEY_MULTI_LIMIT, DEFAULT_MULTI_LIMIT)
        set(value) {
            prefs.edit()
                .putInt(KEY_MULTI_LIMIT, value.coerceIn(TopologyConcurrency.MIN_MULTI_LIMIT, TopologyConcurrency.MAX_MULTI_LIMIT))
                .apply()
        }

    /** 把 [AUTO] 按总大小展开成具体拓扑；未知大小时保守走单连接。 */
    fun resolve(totalSize: Long?): TransportTopology = when (topology) {
        TransportTopology.AUTO ->
            if (totalSize != null && totalSize >= AUTO_MULTI_THRESHOLD_BYTES) {
                TransportTopology.MULTI_CONNECTION
            } else {
                TransportTopology.REUSE
            }
        else -> topology
    }

    private companion object {
        const val KEY_TOPOLOGY = "topology"
        const val KEY_MULTI_LIMIT = "multi_connection_limit"
        const val DEFAULT_MULTI_LIMIT = 8
        const val AUTO_MULTI_THRESHOLD_BYTES = 8L * 1024 * 1024
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
    private val topology: () -> TransportTopology,
    private val resolve: (Long?) -> TransportTopology = { topology() }
) : RangeSource {

    private val multiDelegate = lazy(multiConnection)
    private val multi: RangeSource get() = multiDelegate.value

    override suspend fun probe(url: String, linkId: String?): ProbeResult =
        current(null).probe(url, linkId)

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload = current(null).readRange(url, start, endInclusive, onProgress, linkId)

    override suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload =
        current(totalSize).readRangeChecked(url, start, endInclusive, totalSize, onProgress, linkId)

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = current(null).readSequential(url, start, onProgress, onBytes)

    private fun current(totalSize: Long?): RangeSource = when (resolve(totalSize)) {
        TransportTopology.REUSE -> reuse
        TransportTopology.MULTI_CONNECTION -> multi
        TransportTopology.AUTO -> reuse
    }

    override fun resetConnections() {
        reuse.resetConnections()
        if (multiDelegate.isInitialized()) multiDelegate.value.resetConnections()
    }
}
