package com.yadown.core.peer

import java.io.ByteArrayInputStream
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/** peer 的本机身份：证书与私钥。生产实现来自 AndroidKeyStore，测试实现来自固定证书。 */
interface PeerIdentity {
    val certificate: X509Certificate
    val privateKey: PrivateKey
    val fingerprint: String get() = PeerCertificate.fingerprint(certificate)
    val shortCode: String get() = PeerCertificate.shortCode(fingerprint)
}

object PeerCertificate {

    fun fingerprint(certificate: X509Certificate): String = sha256Hex(certificate.encoded)

    /**
     * 6 位配对短码，由证书指纹前 4 字节派生。
     * 两端对同一证书必须得到相同短码；用户比对的是屏幕上显示的这 6 位数字。
     */
    fun shortCode(fingerprint: String): String {
        val head = fingerprint.take(8).lowercase()
        val value = head.toLongOrNull(16) ?: 0L
        return (value % 1_000_000L).toString().padStart(6, '0')
    }

    fun parseCertificate(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    fun parsePrivateKey(der: ByteArray, algorithm: String = "RSA"): PrivateKey =
        KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
}
