package com.yadown.core.peer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 已配对 peer 的 manifest 查询。客户端只信任配对时固定的证书指纹；
 * 返回的 manifest 还要由 [PeerSourceProvider] 与本地任务的身份做交叉校验。
 */
interface PeerManifestSource {
    suspend fun fetch(host: String, port: Int, contentKey: String, fingerprint: String): PeerManifest?
}

class PeerManifestClient(
    private val clientFactory: (String) -> OkHttpClient = { fingerprint ->
        PeerTls.client(
            PeerTls.clientTlsForFingerprint(fingerprint),
            connectTimeoutMs = 3_000,
            readTimeoutMs = 3_000
        )
    }
) : PeerManifestSource {

    override suspend fun fetch(
        host: String,
        port: Int,
        contentKey: String,
        fingerprint: String
    ): PeerManifest? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(PeerUrls.lookup(host, port, contentKey))
            .header("Accept", PeerHeaders.ACCEPT)
            .build()
        runCatching {
            clientFactory(fingerprint).newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                if (body.length > MAX_MANIFEST_BYTES) return@use null
                PeerProtocol.json.decodeFromString<PeerManifest>(body)
            }
        }.getOrNull()?.takeIf { it.version == 1 && it.contentKey == contentKey }
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 64 * 1024
    }
}
