package com.yadown.core.net

import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 把「当前可用链路 × 源」展开成候选端点，并缓存每链路的传输客户端。
 *
 * 少于两条可用链路时不聚合，直接返回系统默认路由：单链路场景保持原行为，
 * 也避免把同一物理链路重复计入源健康度。
 */
@Singleton
class LinkRegistry @Inject constructor(
    private val aggregator: LinkAggregator,
    private val settings: LinkAggregationSettings,
    val health: LinkHealthStore
) {

    private val clients = ConcurrentHashMap<String, OkHttpRangeSource>()

    @Volatile
    private var cachedLinks: List<NetworkLink> = emptyList()

    @Volatile
    private var cachedAt: Long = 0L

    suspend fun endpointsFor(url: String, kind: SourceKind): List<SourceEndpoint> =
        withContext(Dispatchers.IO) {
            if (!settings.enabled) return@withContext listOf(SourceEndpoint(url, kind))
            val links = links().filter { link ->
                LinkAllocator.allowed(url, link, settings.allowMetered) && health.isUsable(link.id)
            }
            if (links.size < 2) return@withContext listOf(SourceEndpoint(url, kind))
            links.forEach { link ->
                clients.computeIfAbsent(link.id) {
                    OkHttpRangeSource(aggregator.bindRequest(link))
                }
            }
            links.map { link -> SourceEndpoint(url, kind, linkId = link.id) }
        }

    fun sourceFor(linkId: String): RangeSource? = clients[linkId]

    fun invalidate() {
        cachedAt = 0L
    }

    fun links(): List<NetworkLink> {
        val now = System.currentTimeMillis()
        if (now - cachedAt <= TTL_MS) return cachedLinks
        synchronized(this) {
            if (now - cachedAt <= TTL_MS) return cachedLinks
            cachedLinks = runCatching { aggregator.availableLinks() }.getOrDefault(emptyList())
            cachedAt = now
            return cachedLinks
        }
    }

    private companion object {
        const val TTL_MS = 15_000L
    }
}
