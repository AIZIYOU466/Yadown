package com.yadown.core.source

import com.yadown.core.net.ProbeResult
import java.net.URI

/** 源类别决定本地性加成；成本按每字节归一化到 0..1。 */
enum class SourceKind(val localityBonus: Double) {
    ORIGIN(0.0),
    MIRROR(0.3),
    PEER(0.6),
    LOCAL(1.0)
}

data class SourceEndpoint(
    val url: String,
    val kind: SourceKind = SourceKind.ORIGIN,
    val costPerByte: Double = 0.0,
    /** 绑定链路；null 表示系统默认路由（不聚合）。 */
    val linkId: String? = null
) {
    /** 域名 + 路径前两级；不含 query，避免签名参数让同一源分裂成无数 key。链路维度单独追加，同一 URL 的不同链路各自学习健康度。 */
    val healthKey: String
        get() = SourceKey.of(url) + (linkId?.takeIf { it.isNotBlank() }?.let { "|$it" } ?: "")
}

data class SourceRoute(
    val endpoint: SourceEndpoint,
    val score: Double,
    val reliability: Double,
    val throughputScore: Double,
    val connectionLimit: Int,
    val usable: Boolean
) {
    val healthKey: String get() = endpoint.healthKey
    val url: String get() = endpoint.url
    val linkId: String? get() = endpoint.linkId
}

object SourceKey {
    fun of(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "unknown"
        val host = uri.host?.lowercase() ?: "unknown"
        val segments = uri.path.orEmpty()
            .split('/')
            .filter { it.isNotBlank() }
            .take(2)
        return if (segments.isEmpty()) host else "$host/${segments.joinToString("/")}"
    }
}

/**
 * 源站/镜像的内容身份校验：只有能证明「同一份字节」的镜像才允许参与分块下载。
 *
 * 分块校验和只能证明「这个块没写坏」，不能证明「各源给的是同一个文件」；
 * 若两个镜像的 ETag/Last-Modified 不一致，混拉会产生拼接错误且无法在分块层发现。
 * 因此在建任务时就拒绝身份不一致的镜像，而不是等完成校验时才失败。
 */
object ContentIdentity {
    fun matches(primary: ProbeResult, candidate: ProbeResult): Boolean {
        if (primary.totalSize == null || candidate.totalSize == null) return false
        if (primary.totalSize != candidate.totalSize) return false
        if (primary.rangeSupported != candidate.rangeSupported) return false
        if (!primary.rangeSupported) return false
        val etag = primary.etag
        val otherEtag = candidate.etag
        if (etag != null && otherEtag != null) return etag == otherEtag
        val modified = primary.lastModified
        val otherModified = candidate.lastModified
        return modified != null && otherModified != null && modified == otherModified
    }
}

/**
 * 源评分：可靠性 0.55 + 吞吐 0.30 + 本地性 0.15 - 成本 0.10。
 * 无样本的源用中性先验（可靠性 0.8、吞吐 0.5），避免冷启动时被一个差样本源永远压住。
 */
class SourceRanker(private val model: SourceHealthModel = SourceHealthModel()) {

    fun rank(
        endpoints: List<SourceEndpoint>,
        health: Map<String, SourceHealth?>
    ): List<SourceRoute> {
        val unique = endpoints.distinctBy { it.url to it.linkId }
        val maxThroughput = unique.mapNotNull { health[it.healthKey]?.throughput }
            .maxOrNull()
            ?.coerceAtLeast(1.0) ?: 1.0

        return unique.map { endpoint ->
            val sample = health[endpoint.healthKey]
            val reliability = sample?.let { 1.0 - it.failureRate } ?: NEUTRAL_RELIABILITY
            val throughputScore = sample?.let {
                (it.throughput / maxThroughput).coerceIn(0.0, 1.0)
            } ?: NEUTRAL_THROUGHPUT
            val cost = endpoint.costPerByte.coerceIn(0.0, 1.0)
            val score = W_RELIABILITY * reliability +
                W_THROUGHPUT * throughputScore +
                W_LOCALITY * endpoint.kind.localityBonus -
                W_COST * cost
            SourceRoute(
                endpoint = endpoint,
                score = score,
                reliability = reliability,
                throughputScore = throughputScore,
                connectionLimit = model.connectionLimit(sample),
                usable = model.isUsable(sample)
            )
        }.sortedWith(
            compareByDescending<SourceRoute> { it.usable }
                .thenByDescending { it.score }
        )
    }

    companion object {
        const val NEUTRAL_RELIABILITY = 0.8
        const val NEUTRAL_THROUGHPUT = 0.5
        const val W_RELIABILITY = 0.55
        const val W_THROUGHPUT = 0.30
        const val W_LOCALITY = 0.15
        const val W_COST = 0.10
    }
}

class SourceRouter(
    private val registry: SourceHealthRegistry,
    private val ranker: SourceRanker = SourceRanker()
) {

    suspend fun rank(endpoints: List<SourceEndpoint>): List<SourceRoute> {
        val health = endpoints.associate { it.healthKey to registry.get(it.healthKey) }
        return ranker.rank(endpoints, health)
    }

    /**
     * 逐块选源：`slot` 由 chunk index + 源游标决定，使多源并行分担负载；
     * 某个源在途数达到学习上限时跳到下一个源。全部满员时回退到 slot 对应源，
     * 全局并发仍由 DispatcherPolicy 控制，不会因回退突破连接预算。
     */
    fun pick(routes: List<SourceRoute>, slot: Int, inFlight: (String) -> Int): SourceRoute =
        SourceSelection.pick(routes, slot, inFlight)
}

object SourceSelection {
    fun pick(
        routes: List<SourceRoute>,
        slot: Int,
        inFlight: (String) -> Int
    ): SourceRoute {
        require(routes.isNotEmpty()) { "至少需要一个源" }
        // 已知不可用的源只在没有其它选择时使用，避免按轮转稳定命中坏源。
        val pool = routes.filter { it.usable }.ifEmpty { routes }
        val start = Math.floorMod(slot, pool.size)
        for (offset in pool.indices) {
            val candidate = pool[(start + offset) % pool.size]
            if (inFlight(candidate.healthKey) < candidate.connectionLimit) return candidate
        }
        return pool[start]
    }
}
