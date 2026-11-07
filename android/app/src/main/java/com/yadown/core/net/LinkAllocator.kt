package com.yadown.core.net

import java.net.URI

/**
 * 链路准入策略。
 *
 * 蜂窝链路绑定的 socket 只能访问公网出口；把它指向 192.168/内网主机名必然失败，
 * 还会污染链路健康度，因此私网/局域网 URL 只允许 Wi-Fi 与以太网。
 */
object LinkAllocator {

    fun allowed(url: String, link: NetworkLink, allowMetered: Boolean): Boolean {
        if (!link.validated) return false
        if (link.metered && !allowMetered) return false
        if (isLocalOrPrivate(url)) return link.transport != LinkTransport.CELLULAR
        return true
    }

    fun isLocalOrPrivate(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()
            ?.lowercase()
            ?.removeSurrounding("[", "]")
            ?.substringBefore('%')
            ?.takeIf { it.isNotBlank() }
            ?: return true
        if (host.contains(':')) {
            return host == "::1" ||
                host.startsWith("fe8") || host.startsWith("fe9") ||
                host.startsWith("fea") || host.startsWith("feb") ||
                host.startsWith("fc") || host.startsWith("fd")
        }
        if (host == "localhost" || host.endsWith(".local") || !host.contains('.')) return true
        val octets = host.split('.')
        if (octets.size != 4) return false
        val first = octets[0].toIntOrNull() ?: return false
        val second = octets[1].toIntOrNull() ?: return false
        return first == 10 || first == 127 ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168) ||
            (first == 169 && second == 254)
    }
}
