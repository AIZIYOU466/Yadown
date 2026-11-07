package com.yadown.core.support

import com.yadown.core.peer.PeerCertificate
import com.yadown.core.peer.PeerIdentity
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Base64

/**
 * 固定测试身份。证书与私钥只用于 JVM 单测验证 TLS 协议，与生产 AndroidKeyStore 无关；
 * 有效期 100 年，SAN 含 127.0.0.1，便于本地回环连接。
 */
object TestPeerIdentities {

    val a: PeerIdentity by lazy { identity(CERT_A, KEY_A) }
    val b: PeerIdentity by lazy { identity(CERT_B, KEY_B) }

    private fun identity(cert: String, key: String): PeerIdentity = StaticPeerIdentity(
        certificate = PeerCertificate.parseCertificate(der(cert)),
        privateKey = PeerCertificate.parsePrivateKey(der(key))
    )

    private fun der(text: String): ByteArray = Base64.getDecoder().decode(text.trim())

    private const val CERT_A =
        "MIIDLjCCAhagAwIBAgIUaD72SRNovKlzh4J/8IkRAjyWH7owDQYJKoZIhvcNAQELBQAwHTEb" +
            "MBkGA1UEAwwSWWFkb3duIFRlc3QgUGVlciBhMCAXDTI2MTEwNjIzMzAwMVoYDzIxMjYxMDEz" +
            "MjMzMDAxWjAdMRswGQYDVQQDDBJZYWRvd24gVGVzdCBQZWVyIGEwggEiMA0GCSqGSIb3DQEB" +
            "AQUAA4IBDwAwggEKAoIBAQC92gIB2D71yTkuJ+3KHotzB0hP4L+CtALrjWfFUKggBOASiTXW" +
            "wxIWg+ikXHqyZ1A4bja60UjONwOUnr/NJ1Zwb65MSPKDPP5KDKSoG1wdGR2uY9VsIOveqb08" +
            "iCLMbYC5iU2yGkCelNozhYYiK10hZyU4jLfEXXsmRTfexByWo5SDgdDKooDK9T2NsH/cWRse" +
            "GUi8XWMAK07SM7/qwaj+2+TeD+nN4Tw90ImCg8a1J3qpbLyituirKFGNK3Z6tzDXucawbksA" +
            "EGsbVk6sdQXU3rx/6zrC2IpSkDZ0awpRpUeZCKt6nCUFRgB4kfI5Xonz11dH+1yQtPuR/OKS" +
            "rXz9AgMBAAGjZDBiMB0GA1UdDgQWBBTTcAxNaWfr3BmHovMYpKxsKgttCzAfBgNVHSMEGDAW" +
            "gBTTcAxNaWfr3BmHovMYpKxsKgttCzAPBgNVHRMBAf8EBTADAQH/MA8GA1UdEQQIMAaHBH8A" +
            "AAEwDQYJKoZIhvcNAQELBQADggEBAIDB+69JG8MSwrdSU0N2M7Vse0LeuEsulNQ0AKWO8FOC" +
            "mveK6UjBIEWXR3ZKorYi5FJXf5f7cSzcaq/0wwbfqrGj4C42EuSV0s+Ng24Mw2eNIcfNHPnz" +
            "OnhnytG79Cf5CxavwO/nZRNCWLp92/sUgwACwJg2BmDTdNk5bjoOIikgWjF8lbULAvXo23d9" +
            "N/h0QJjqLB3PEeuqxRf471g4RkHsopZGeerMTJ5Gpe9iS+5R8DJhHA/3VYwArT6zLCmejqS4" +
            "ow9EHOuMRm6B8UZXp/PStaw3Cx+tHDuqEDGtHPKI6srI7v5vzJXcFUoIaOIi3BrJN2hgCQVR" +
            "3o3TsDfCpeA="

    private const val KEY_A =
        "MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQC92gIB2D71yTkuJ+3KHotz" +
            "B0hP4L+CtALrjWfFUKggBOASiTXWwxIWg+ikXHqyZ1A4bja60UjONwOUnr/NJ1Zwb65MSPKD" +
            "PP5KDKSoG1wdGR2uY9VsIOveqb08iCLMbYC5iU2yGkCelNozhYYiK10hZyU4jLfEXXsmRTfe" +
            "xByWo5SDgdDKooDK9T2NsH/cWRseGUi8XWMAK07SM7/qwaj+2+TeD+nN4Tw90ImCg8a1J3qp" +
            "bLyituirKFGNK3Z6tzDXucawbksAEGsbVk6sdQXU3rx/6zrC2IpSkDZ0awpRpUeZCKt6nCUF" +
            "RgB4kfI5Xonz11dH+1yQtPuR/OKSrXz9AgMBAAECggEAJ7bqs5MWfJkG9qJQqnbgSgRMerUz" +
            "qrZYZAkVG6AnQ2dxrj74vb/Z0U7vxC46VoapL9zSYxszpTu56oWY7uQbP3MFXZzm6lBAi6IA" +
            "cdAjYUznOhtVQoTSgqY0WWzLBsOjaWyRDcBTIzlVOfDlIdG7bijjDuTU7mV1xQg2y0lwB4y8" +
            "j9/vSta0Bw5IVgIPwkG7RueYqtMKQIQ72Wl8/LQy8aJ2XEWo94ndiyv5d2SB/7/IrsVhBSC5" +
            "KYcEAiWG/my1SJLqRaLgEkjEkFTJaEYw/LG3aPVZOJzKyFHghC/xkDp8gIUejZdYQHKkNuoq" +
            "ol1xPnqVpKCHxfpWIb9EaQOOYQKBgQD7zCCQTZDA7/fbLVlBo9EQ7gruLS3IkN3NH1WZPkBL" +
            "4muezMXsbwFpSMHoeESttzvd5KRcUL0OYV7aC0qplwLXNDjnFmyrmOqVTgTbUqW9FAmrUcnF" +
            "G+tEwxaSodYj23yeysjUJDhxf6JHw8pI09xZej9lNcYlNBT3e0q7ymlkjQKBgQDBBTNQEPOh" +
            "0JVBW1HSpeQd23zSrRGJuwXVBmolU2cbQP46MkP2keIcJ8q2TUsDXRB0sevxd3xh1Qn2nNdu" +
            "4VasGB0dZkdkIZo/Irai2EzM6jLtCPT3pWoATxGAra8mafuktO+EYL7ipc5b0yBGPcepMHHN" +
            "p4sdYL/x78+WOlm2MQKBgDVtgNNdwgNOZ9c37iKBchEXkXYsW/Sc2JN3vKABl38Ckjdl1/Ft" +
            "NG10pMCBFIGPycIxi7aS4yqz+n5luvf1NQMY76IJHhHjJpN7tIgdJ1HReKPDfP64B9nVU2rV" +
            "sCx3nB/Ng4OQE/HxVSvJHZkKa00JJX6CRIMMZA6HOLpUHrzZAoGAdBXjmXjIff5LdcYtPKom" +
            "uspBlRcfYB1J0aeM+Svi6+I3w9ptQ4LTYSmRYfi0b65t8jD7fPrmpJQS7O3BJ1+A1/mcsQzN" +
            "TOxkryn6e4/pCKilvidP5Qzn5o2nMiPDHjBJdvKWxp/Ezdz4cgNU+sm3JgsLtqrYIPAKIMCg" +
            "sXIgeFECgYBe0IEWhHOoVbib6sY8U3ynG3e523xXIfMaRkRwPvoSIyGD4Xk8+BfIMCnzVPvN" +
            "iHiBArOksgBxdY83hRoBxr1Ir/hKhvtoFC9yWUqKV+WukG+4ngtN3wX6V2m8Z0pkFNq7ejMT" +
            "2bm10rni+lm+U4Gf+dZNV3dNy6aQ5EDKx16l2g=="

    private const val CERT_B =
        "MIIDLjCCAhagAwIBAgIUHjHP0BjnNvxu3NFEzDmXT4NhSiswDQYJKoZIhvcNAQELBQAwHTEb" +
            "MBkGA1UEAwwSWWFkb3duIFRlc3QgUGVlciBiMCAXDTI2MTEwNjIzMzAwMloYDzIxMjYxMDEz" +
            "MjMzMDAyWjAdMRswGQYDVQQDDBJZYWRvd24gVGVzdCBQZWVyIGIwggEiMA0GCSqGSIb3DQEB" +
            "AQUAA4IBDwAwggEKAoIBAQC4AUYprHdRxEMDeffGHohnLrPeX3pYH35S3CsNR0+s9dzqkg9b" +
            "w9oif0tOIeWWroTNxKYM6BXqj1DYR/sYVbWy3A1LnXAUPlsBndce1+CbbaomeojkoGnj5Lwg" +
            "e0twLVjMReFP6H6Ri1txDCyQDDux5fZ3Ec/T0SBmvbaD2x2BnsQ5Ge3KhiFu54vW70cX1Ev9" +
            "7LvSdAuC9u+Ofaaeb270g7PvGOQuzmJDIgJVS+XVHcNgpziR5C/6OIT9PyxnBrRmgUP3ud96" +
            "8XPIfsGpgh5dtzPDPZaTrdjXuBU7TghiUHo9cOiP2OL9wLUlWQAjcv9gztk+l5f3d/9xs2FE" +
            "bQaZAgMBAAGjZDBiMB0GA1UdDgQWBBTQCCIhGXn5xurcykKa0Ow0Mq6bdTAfBgNVHSMEGDAW" +
            "gBTQCCIhGXn5xurcykKa0Ow0Mq6bdTAPBgNVHRMBAf8EBTADAQH/MA8GA1UdEQQIMAaHBH8A" +
            "AAEwDQYJKoZIhvcNAQELBQADggEBAJszhZFo9KkwzcvXgMXmJ/V+OVMGzNGeiJm7CNqD9PZN" +
            "CHfIyUmDGQNKmSGAveBRijwK/cWGfhRYi9ant6ReoJALrl4OgIRMsFlog5yVAOSQ0M3FU6g1" +
            "e08jdLAsBXXRbrNy25jKSNRZfdBBU4WPqiLG4HMymnWrZFgS7qrJGCH/Mf/AoMLTzm5eD+KH" +
            "QDZj3NdQPIKUl3++XjTqA529IEp8BJHNUWi7uJMu7ecf/ObJrDdksNo4EQxrbxCdR5gAEH7M" +
            "sSJSGlkQDPIz7OabY/9zT18wnZ1TK4UWoqS4e+G1Sx6OZeHd0qEQgTpSMTZ2GrZXusfYHy+W" +
            "WF5NHQ1KWbw="

    private const val KEY_B =
        "MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC4AUYprHdRxEMDeffGHohn" +
            "LrPeX3pYH35S3CsNR0+s9dzqkg9bw9oif0tOIeWWroTNxKYM6BXqj1DYR/sYVbWy3A1LnXAU" +
            "PlsBndce1+CbbaomeojkoGnj5Lwge0twLVjMReFP6H6Ri1txDCyQDDux5fZ3Ec/T0SBmvbaD" +
            "2x2BnsQ5Ge3KhiFu54vW70cX1Ev97LvSdAuC9u+Ofaaeb270g7PvGOQuzmJDIgJVS+XVHcNg" +
            "pziR5C/6OIT9PyxnBrRmgUP3ud968XPIfsGpgh5dtzPDPZaTrdjXuBU7TghiUHo9cOiP2OL9" +
            "wLUlWQAjcv9gztk+l5f3d/9xs2FEbQaZAgMBAAECggEADLZb0lO9v5qrVZFm3ptr6ZMGrrmS" +
            "coWkfNWErOttFAI9c8BJCto2VxVLztdVLQqcFro8DZ64KuchWvvoG1eKkNOWoI99N4zSTUOK" +
            "IfGgrSRPsc5b6E2z2yCJpcXW4aZkYOKADTz/B4+k7uO8LWyG8ES2uSW0IcpsLmTzd9sayAJh" +
            "o3aiJSaaJMJef+PDcd13Nagrjd0pkIjV+VT5HO6rJJCwVrtl6FVvp2Y9cCQcjXEKVKUV4kP9" +
            "YZYJiIfgGLSyipU8BDwTWzQKorgvvJfwUUMghqoIJieTfN4SoqOSwvUAN42QafdMpj20aEB4" +
            "ApUHB4+Ow83qDJvKG595fDlogQKBgQDvD1svw4az6AdS/4SyfRiua/SKQeWnXelDI3/wsi/z" +
            "leLY/myBXTsrZtTytX4x/KgPU4SeT7LmcF8HnSkrfhBWBXVgOBnPZ/+A/PrzVULYRVMJZvhb" +
            "a8dfqktZqyN0tC7ygZP5MyqSzkDTUw79Kz0MkaFowVQRag7GLTSdvo5TQQKBgQDFCzKvny0K" +
            "Bh5xrZNpcV1YN3gz7/vfCbUsZsDnnOf3sDmz+Wja3mPnq89EqmqIvqh2B8TCILpVHF0xhdTr" +
            "Jsz+2kgHV6R+/XrQKdkgraCZRMrpOUhsCdm+ZmZUTwjJG+cy+hyvnHl4SM89+Gd7hktQejFO" +
            "4kqAoOX0DZqlZ23VWQKBgDaiqdJ2u1hulQ3w++DXGkbywUa/3L6thdwkRt85uh/Ph7LUIvRF" +
            "3HoE3HJD7H18IuStz8dmq5pi4DUbRAd+njkqsjK1X6C5Qfm215u5Rreq0Fl7wRMTxNMmVmY9" +
            "6LKJY5lk5+6yNO6sWMBzYR+XPDKdLeiFFrOMK6g7OKU8ocaBAoGBAKqtA29qAjo/iJlRD3Hz" +
            "Fza1fXIHenT8VKksLAWzPJ6oT6Mb8345yfG/OV6x+muOMh9ipUS90oisc/DUitRRHJnX+zIY" +
            "8UxqRboPtZp/qq94DgasbpXehnRNAHAgsWx5te7LBJP8hp7w+kokY4Z0kcIYxaMdkgkO1YBr" +
            "VDvXdiSpAoGBAKrJyLi2n18pzkcH8lHfTX3hm1dSrt6p1qWGkl6LlG9rGOopk1jL/XxBn3em" +
            "VSQigpgestMyx60AwFhZUq89UY9bF5FdskDCzswdH+ZeRVAR2gcEz2yOK+Y8uDBrvF6njkUU" +
            "NkbRppHQT+4WetxrdBxQ1sXj5CsooHdNN8KhDbOo"
}

class StaticPeerIdentity(
    override val certificate: X509Certificate,
    override val privateKey: PrivateKey
) : PeerIdentity
