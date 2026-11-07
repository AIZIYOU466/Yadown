package com.yadown.core.peer

import com.yadown.core.net.XXHASH_HEADER
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 局域网内容身份：sourceUrl 规范化后取 SHA-256 前缀。
 *
 * 不用原始 URL 作为广播值：TXT 记录对同网段可见，原始链接可能带签名参数或私密路径；
 * 规范化同时让大小写 host、默认端口等写法命中同一内容。
 */
object ContentKey {

    fun of(sourceUrl: String): String? {
        val normalized = normalize(sourceUrl) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.take(KEY_BYTES).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun normalize(sourceUrl: String): String? {
        val uri = runCatching { URI(sourceUrl.trim()) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        // 带凭据的 URL 不参与 peer 匹配，避免把账号信息带进哈希输入。
        if (uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val defaultPort = if (scheme == "https") 443 else 80
        val portPart = if (uri.port > 0 && uri.port != defaultPort) ":${uri.port}" else ""
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        return "$scheme://$host$portPart$path$query"
    }

    private const val KEY_BYTES = 8
}

@Serializable
data class PeerManifest(
    val version: Int = 1,
    val contentId: String,
    val contentKey: String,
    val size: Long,
    val etag: String? = null,
    val lastModified: String? = null,
    val fileName: String = "",
    val mimeType: String? = null
)

object PeerProtocol {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}

object PeerHeaders {
    const val XX_HASH = XXHASH_HEADER
    const val ACCEPT = "application/json"
    const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
    const val CONTENT_TYPE_BYTES = "application/octet-stream"
}

object PeerPaths {
    fun lookup(contentKey: String): String = "/v1/lookup/$contentKey"

    fun content(contentId: String): String = "/v1/content/$contentId"

    fun parse(path: String): PeerRequest? {
        LOOKUP.matchEntire(path)?.let { return PeerRequest.Lookup(it.groupValues[1]) }
        CONTENT.matchEntire(path)?.let { return PeerRequest.Content(it.groupValues[1]) }
        return null
    }

    private val LOOKUP = Regex("""^/v1/lookup/([0-9a-f]{16})$""")
    private val CONTENT = Regex("""^/v1/content/([A-Za-z0-9_-]{1,64})$""")
}

sealed interface PeerRequest {
    data class Lookup(val contentKey: String) : PeerRequest
    data class Content(val contentId: String) : PeerRequest
}

object PeerUrls {
    fun content(host: String, port: Int, contentId: String): String =
        "https://${hostForUrl(host)}:$port${PeerPaths.content(contentId)}"

    fun lookup(host: String, port: Int, contentKey: String): String =
        "https://${hostForUrl(host)}:$port${PeerPaths.lookup(contentKey)}"

    /** IPv6 字面量在 URL 中必须加方括号，链路本地地址的 scope id 不能出现在 URL 里。 */
    fun hostForUrl(host: String): String {
        val clean = host.substringBefore('%')
        return if (clean.contains(':')) "[$clean]" else clean
    }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
