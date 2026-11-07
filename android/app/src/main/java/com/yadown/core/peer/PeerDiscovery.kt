package com.yadown.core.peer

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow

/** 局域网 peer，仅描述发现结果；peer 传输在 P0.2 验证与信任模型落地前不启用。 */
data class Peer(
    val peerId: String,
    val name: String,
    val host: String,
    val port: Int,
    val contentHashes: Set<String>,
    val lastSeenAt: Long,
    val reliability: Double = 0.5,
    /** mDNS 广播的证书指纹（仅用于命中信任库，不能作为配对依据）。 */
    val fingerprint: String? = null
)

interface PeerDiscovery {
    fun discover(): Flow<List<Peer>>

    fun queryContent(contentHash: String): List<Peer>
}

/**
 * 发现结果的纯内存索引：过期过滤、内容匹配与稳定排序。
 * 持久化由调用方写入 `peer_index`，这里不依赖 Android。
 */
class PeerIndex(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val peers = ConcurrentHashMap<String, Peer>()

    fun upsert(peer: Peer) {
        peers[peer.peerId] = peer
    }

    fun remove(peerId: String) {
        peers.remove(peerId)
    }

    fun snapshot(): List<Peer> {
        val now = clock()
        peers.entries.removeIf { now - it.value.lastSeenAt > ttlMs }
        return peers.values.sortedWith(
            compareByDescending<Peer> { it.reliability }
                .thenByDescending { it.lastSeenAt }
                .thenBy { it.name }
        )
    }

    fun queryContent(contentHash: String): List<Peer> =
        snapshot().filter { contentHash in it.contentHashes }

    companion object {
        const val DEFAULT_TTL_MS = 10L * 60 * 1000
    }
}
