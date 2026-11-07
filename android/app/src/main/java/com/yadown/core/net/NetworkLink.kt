package com.yadown.core.net

/** 链路类别，用于准入策略与 UI 展示。 */
enum class LinkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER
}

/**
 * 一条可用网络链路。纯数据，不持有 Android Network，便于纯 JVM 单测；
 * Android 实现内部维护 id -> Network 映射来完成绑定。
 */
data class NetworkLink(
    val id: String,
    val transport: LinkTransport,
    val metered: Boolean,
    val validated: Boolean = true,
    val networkHandle: Long = 0L
) {
    val label: String
        get() = when (transport) {
            LinkTransport.WIFI -> "Wi-Fi"
            LinkTransport.CELLULAR -> "移动数据"
            LinkTransport.ETHERNET -> "以太网"
            LinkTransport.OTHER -> "其它网络"
        }
}
