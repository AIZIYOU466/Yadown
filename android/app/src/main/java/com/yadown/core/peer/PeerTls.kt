package com.yadown.core.peer

import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import okhttp3.OkHttpClient

/**
 * peer TLS 的两端配置。
 *
 * 服务端用本机身份（AndroidKeyStore 私钥）签名握手；客户端只信任指纹已固定的证书，
 * 主机名不参与校验——peer 地址是局域网 IP，自签证书的 CN 无法覆盖所有地址，
 * 证书指纹才是身份锚点，这与信任模型一致。
 */
object PeerTls {

    data class ClientTls(
        val context: SSLContext,
        val trustManager: X509ExtendedTrustManager
    )

    fun serverContext(identity: PeerIdentity): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(IdentityKeyManager(identity)), null, SecureRandom())
        }

    fun clientTlsForFingerprint(fingerprint: String): ClientTls {
        val expected = fingerprint.trim().lowercase()
        val trustManager = object : X509ExtendedTrustManager() {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
                verify(chain)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?
            ) = verify(chain)

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?
            ) = verify(chain)

            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("peer 传输不支持客户端证书")
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?
            ) {
                throw CertificateException("peer 传输不支持客户端证书")
            }

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?
            ) {
                throw CertificateException("peer 传输不支持客户端证书")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            private fun verify(chain: Array<out X509Certificate>?) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("对端没有提供证书")
                if (PeerCertificate.fingerprint(leaf) != expected) {
                    throw CertificateException("peer 证书指纹与已配对记录不一致")
                }
            }
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return ClientTls(context, trustManager)
    }

    /**
     * 配对专用：接受任意证书以便读出真实指纹并计算短码。
     * 只用它完成一次 TLS 握手换取证书，不传输任何内容；用户确认短码后才把指纹写入信任库。
     */
    fun captureTls(): ClientTls {
        val trustManager = object : X509ExtendedTrustManager() {
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?
            ) = Unit
            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?
            ) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                socket: Socket?
            ) = Unit
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
                engine: SSLEngine?
            ) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return ClientTls(context, trustManager)
    }

    fun client(
        tls: ClientTls,
        connectTimeoutMs: Long = 5_000,
        readTimeoutMs: Long = 20_000
    ): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(tls.context.socketFactory, tls.trustManager)
        .hostnameVerifier { _, _ -> true }
        .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .build()

    private class IdentityKeyManager(private val identity: PeerIdentity) : X509ExtendedKeyManager() {
        private val alias = "yadown-peer"

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String>? = null

        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String? = null

        override fun getServerAliases(
            keyType: String?,
            issuers: Array<out Principal>?
        ): Array<String>? = arrayOf(alias)

        override fun chooseServerAlias(
            keyType: String?,
            issuers: Array<out Principal>?,
            socket: Socket?
        ): String = alias

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
            arrayOf(identity.certificate)

        override fun getPrivateKey(alias: String?): PrivateKey? = identity.privateKey
    }
}
