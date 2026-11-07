package com.yadown.core.peer

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * AndroidKeyStore 生成的自签 TLS 身份。私钥不可导出，首次启动时生成并长期复用，
 * 证书指纹因此成为本机的稳定标识。少数 ROM 可能拒绝 RSA+DIGEST_NONE 密钥，
 * 此时返回 null 由上层关闭 peer 功能，不影响主下载。
 */
class AndroidPeerIdentity private constructor(
    override val certificate: X509Certificate,
    override val privateKey: PrivateKey
) : PeerIdentity {

    companion object {
        private const val ALIAS = "yadown-peer-identity"
        private const val DAY_MS = 24L * 60 * 60 * 1000
        // 2100-01-01，避免证书在设备生命周期内过期。
        private const val NOT_AFTER_MS = 4_102_444_800_000L

        fun loadOrCreate(): AndroidPeerIdentity? = runCatching {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!keyStore.containsAlias(ALIAS)) generate()
            val certificate = keyStore.getCertificate(ALIAS) as? X509Certificate
                ?: return@runCatching null
            val privateKey = keyStore.getKey(ALIAS, null) as? PrivateKey
                ?: return@runCatching null
            AndroidPeerIdentity(certificate, privateKey)
        }.getOrNull()

        private fun generate() {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA,
                "AndroidKeyStore"
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    // TLS 栈自己生成摘要，只允许无摘要签名；TLS 1.2 的 RSA 签名使用 PKCS1。
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(X500Principal("CN=Yadown Peer"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - DAY_MS))
                    .setCertificateNotAfter(Date(NOT_AFTER_MS))
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            generator.generateKeyPair()
        }
    }
}
