package com.yadown.core.peer

import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient

/**
 * 已配对 peer 的 TLS 客户端缓存。按 host:port 精确匹配，避免把普通 HTTPS 主源
 * 误路由到指纹固定的客户端；指纹变化时创建新客户端（旧连接自然失效）。
 */
class PeerClientRegistry(
    private val clientFactory: (String) -> OkHttpClient = { fingerprint ->
        PeerTls.client(PeerTls.clientTlsForFingerprint(fingerprint))
    }
) {

    private data class Entry(
        val fingerprint: String,
        val source: com.yadown.core.net.OkHttpRangeSource
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    fun trust(host: String, port: Int, fingerprint: String) {
        val key = key(host, port)
        val normalized = fingerprint.trim().lowercase()
        val existing = entries[key]
        if (existing?.fingerprint == normalized) return
        val client = clientFactory(normalized)
        entries[key] = Entry(normalized, com.yadown.core.net.OkHttpRangeSource(client))
    }

    fun forget(host: String, port: Int) {
        entries.remove(key(host, port))
    }

    fun clear() {
        entries.clear()
    }

    fun sourceFor(url: String): com.yadown.core.net.OkHttpRangeSource? {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 443
        return entries[key(host, port)]?.source
    }

    private fun key(host: String, port: Int): String =
        "${host.lowercase().substringBefore('%')}:$port"
}
