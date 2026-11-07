package com.yadown.core.peer

import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PeerHandshakeResult(
    val host: String,
    val port: Int,
    val fingerprint: String,
    val shortCode: String
)

/**
 * 配对手握：只建立 TLS 连接并读出对端证书，不发送任何内容请求。
 * 短码由对端证书实时计算，因此用户比对的是实际连接到的设备，而不是 mDNS 广播里的声明。
 */
class PeerHandshake(
    private val tlsFactory: () -> PeerTls.ClientTls = { PeerTls.captureTls() },
    private val timeoutMs: Int = 3_000
) {

    suspend fun probe(host: String, port: Int): PeerHandshakeResult? = withContext(Dispatchers.IO) {
        runCatching {
            val tls = tlsFactory()
            val socket = tls.context.socketFactory.createSocket() as SSLSocket
            socket.use { connection ->
                connection.connect(InetSocketAddress(host, port), timeoutMs)
                connection.soTimeout = timeoutMs
                connection.startHandshake()
                val leaf = connection.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: return@runCatching null
                val fingerprint = PeerCertificate.fingerprint(leaf)
                PeerHandshakeResult(
                    host = host,
                    port = port,
                    fingerprint = fingerprint,
                    shortCode = PeerCertificate.shortCode(fingerprint)
                )
            }
        }.getOrNull()
    }
}
