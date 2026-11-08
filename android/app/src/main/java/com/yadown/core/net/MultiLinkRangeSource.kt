package com.yadown.core.net

import kotlinx.coroutines.CancellationException

/**
 * 多链路传输层：按 route 携带的 linkId 选择该链路的客户端，其余请求交给 delegate。
 *
 * 链路失败直接抛给下载引擎：引擎已有 `sourceSlot+1` 换源与重试预算，
 * 这里再包一层重试只会放大请求数。链路健康由 [LinkHealthStore] 记录，
 * 下一次选路时不可用链路会被过滤。
 */
class MultiLinkRangeSource(
    private val delegate: RangeSource,
    private val linkSources: (String) -> RangeSource?,
    private val health: LinkHealthStore,
    private val clock: () -> Long = System::currentTimeMillis
) : RangeSource {

    override suspend fun probe(url: String, linkId: String?): ProbeResult {
        val source = sourceOrNull(linkId) ?: return delegate.probe(url)
        return source.probe(url)
    }

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
        val link = linkOrNull(linkId) ?: return delegate.readRangeChecked(
            url, start, endInclusive, totalSize, onProgress, null
        )
        val source = linkSources(link) ?: return delegate.readRangeChecked(
            url, start, endInclusive, totalSize, onProgress, null
        )
        val startedAt = clock()
        return try {
            val payload = source.readRangeChecked(url, start, endInclusive, totalSize, onProgress, null)
            if (payload.statusCode == 206 && payload.bytes.isNotEmpty()) {
                health.recordSuccess(
                    link,
                    payload.bytes.size.toLong(),
                    (clock() - startedAt).coerceAtLeast(1)
                )
            }
            payload
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            health.recordFailure(link)
            throw e
        }
    }

    override suspend fun readSequential(
        url: String,
        start: Long,
        onProgress: (Long) -> Unit,
        onBytes: (ByteArray) -> Unit
    ): SequentialResult = delegate.readSequential(url, start, onProgress, onBytes)

    override fun criticalPhaseSource(): RangeSource =
        MultiLinkRangeSource(delegate.criticalPhaseSource(), linkSources, health, clock)

    override fun resetConnections() = delegate.resetConnections()

    private fun sourceOrNull(linkId: String?): RangeSource? =
        linkOrNull(linkId)?.let(linkSources)

    private fun linkOrNull(linkId: String?): String? =
        linkId?.takeIf { it.isNotBlank() }
}
