package com.yadown.p0.multilink

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * T0.1 多链路聚合探针。
 *
 * 假设：绑定 Wi-Fi 与蜂窝两条 Network 后，可同时发起 Range 请求并从各自链路取数据。
 * 判定：双链路吞吐 ≥ 单链路 1.5x 视为通过。
 *
 * 依赖官方 API：ConnectivityManager.getAllNetworks/getNetworkCapabilities、
 * Network.getSocketFactory（API 21+）。真机执行后回填 p0/README.md 记录表。
 */
object MultiLinkProbe {

    data class LinkResult(
        val transport: String,
        val bytes: Long,
        val elapsedMs: Long
    ) {
        val bytesPerSec: Double
            get() = if (elapsedMs > 0) bytes * 1000.0 / elapsedMs else 0.0
    }

    /** 返回当前可用的 [("wifi"|"cellular", Network)] 列表。 */
    fun findNetworks(cm: ConnectivityManager): List<Pair<String, Network>> =
        cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi" to network
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular" to network
                else -> null
            }
        }

    /**
     * 对每条可用链路各发一个 [Range: bytes=0-([rangeEnd])] 请求。
     * 每个请求通过 `network.socketFactory` 绑定到对应链路，这是 Android 官方推荐的
     * "按 Network 发起请求"的方式，无需全局 bindProcessToNetwork。
     */
    fun probe(context: Context, url: String, rangeEnd: Long = 1023): List<LinkResult> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        return findNetworks(cm).map { (transport, network) ->
            val client = OkHttpClient.Builder()
                .socketFactory(network.socketFactory)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-$rangeEnd")
                .build()
            val startNs = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val body = response.body?.bytes()
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                LinkResult(transport, body?.size?.toLong() ?: 0L, elapsedMs)
            }
        }
    }

    /** 单链路基线：绑定第一条可用链路，重复请求 n 次取中位数吞吐。 */
    fun singleLinkBaseline(context: Context, url: String, rangeEnd: Long = 1023, rounds: Int = 3): Double {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return 0.0
        val network = findNetworks(cm).firstOrNull()?.second ?: return 0.0
        val client = OkHttpClient.Builder().socketFactory(network.socketFactory).build()
        val bytesPerSec = (1..rounds).map {
            val request = Request.Builder().url(url).header("Range", "bytes=0-$rangeEnd").build()
            val startNs = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes()?.size?.toLong() ?: 0L
                val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                if (elapsedMs > 0) bytes * 1000.0 / elapsedMs else 0.0
            }
        }.sorted()
        return bytesPerSec[bytesPerSec.size / 2]
    }
}