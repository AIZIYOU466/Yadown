package com.yadown.core.peer

import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 把已配对的局域网 peer 转换成可参与调度的源。
 *
 * 身份校验比镜像更严：peer 必须证明自己持有与本地任务同一份字节
 * （contentKey + 大小 + 强 ETag 或 Last-Modified），否则拒绝。
 * 分块内容由 [PeerRangeSource] 再按响应头里的 xxHash64 校验。
 */
class PeerSourceProvider(
    private val discovery: PeerDiscovery,
    private val trust: PeerTrustStore,
    private val manifests: PeerManifestSource,
    private val clients: PeerClientRegistry
) {

    suspend fun endpointsFor(intent: IntentEntity): List<SourceEndpoint> = coroutineScope {
        val contentKey = ContentKey.of(intent.sourceUrl) ?: return@coroutineScope emptyList()
        val size = intent.totalSize ?: return@coroutineScope emptyList()
        // 弱 ETag 不能证明字节级一致（与 RangeValidator 的口径一致），此时退回 Last-Modified。
        val strongEtag = intent.etag?.takeUnless { it.startsWith("W/") }
        val lastModified = intent.lastModified
        if (strongEtag == null && lastModified == null) return@coroutineScope emptyList()

        discovery.queryContent(contentKey)
            .filter { peer -> peer.fingerprint?.let { trust.isTrusted(it) } == true }
            .distinctBy { peer -> "${peer.host}:${peer.port}" }
            .map { peer ->
                async {
                    val fingerprint = peer.fingerprint ?: return@async null
                    val manifest = manifests.fetch(peer.host, peer.port, contentKey, fingerprint)
                        ?: return@async null
                    // contentId 来自对端 manifest，只允许安全字符后才拼进请求路径。
                    if (!CONTENT_ID.matches(manifest.contentId)) return@async null
                    if (!identityMatches(manifest, contentKey, size, strongEtag, lastModified)) {
                        return@async null
                    }
                    clients.trust(peer.host, peer.port, fingerprint)
                    SourceEndpoint(
                        url = PeerUrls.content(peer.host, peer.port, manifest.contentId),
                        kind = SourceKind.PEER
                    )
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private fun identityMatches(
        manifest: PeerManifest,
        contentKey: String,
        size: Long,
        strongEtag: String?,
        lastModified: String?
    ): Boolean {
        if (manifest.contentKey != contentKey) return false
        if (manifest.size != size) return false
        return when {
            strongEtag != null -> manifest.etag == strongEtag
            lastModified != null -> manifest.lastModified == lastModified
            else -> false
        }
    }

    private companion object {
        val CONTENT_ID = Regex("""^[A-Za-z0-9_-]{1,64}$""")
    }
}
