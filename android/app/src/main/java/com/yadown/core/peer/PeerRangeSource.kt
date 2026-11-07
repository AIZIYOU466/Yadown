package com.yadown.core.peer

import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.net.SequentialResult
import com.yadown.core.transfer.DownloadException
import com.yadown.core.transfer.FailureCause
import com.yadown.core.transfer.XxHash64

/**
 * 按 URL 把已配对 peer 的请求路由到指纹固定的客户端，其余请求保持原样。
 *
 * peer 响应必须带分块 xxHash64：TLS 只保证传输完整性，不能证明对端给的是
 * 与主源同一份内容；缺少校验头的 peer 一律按内容不可信失败，不做降级放行。
 */
class PeerRangeSource(
    private val delegate: RangeSource,
    private val peers: PeerClientRegistry
) : RangeSource {

    override suspend fun probe(url: String, linkId: String?): ProbeResult =
        peers.sourceFor(url)?.probe(url) ?: delegate.probe(url, linkId)

    override suspend fun readRange(
        url: String,
        start: Long,
        endInclusive: Long,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload = readRangeChecked(url, start, endInclusive, null, onProgress, linkId)

    override suspend fun readRangeChecked(
        url: String,
        start: Long,
        endInclusive: Long,
        totalSize: Long?,
        onProgress: (Long) -> Unit,
        linkId: String?
    ): RangePayload {
        val peer = peers.sourceFor(url)
        val payload = if (peer == null) {
            delegate.readRangeChecked(url, start, endInclusive, totalSize, onProgress, linkId)
        } else {
            peer.readRangeChecked(url, start, endInclusive, totalSize, onProgress, null)
        }
        if (peer != null) PeerChunkVerifier.verify(payload)
        return payload
    }

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = delegate.readSequential(url, start, onProgress, onBytes)

    override fun resetConnections() = delegate.resetConnections()
}

object PeerChunkVerifier {

    fun verify(payload: RangePayload) {
        if (payload.statusCode != 206) return
        val declared = payload.peerXxHash64
            ?: throw DownloadException(
                FailureCause.CHECKSUM_MISMATCH,
                "peer 响应缺少分块 xxHash64，拒绝使用"
            )
        if (XxHash64.hash(payload.bytes) != declared) {
            throw DownloadException(FailureCause.CHECKSUM_MISMATCH, "peer 分块 xxHash64 不匹配")
        }
    }
}
