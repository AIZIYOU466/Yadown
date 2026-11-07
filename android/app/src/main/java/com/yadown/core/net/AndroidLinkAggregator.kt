package com.yadown.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Android 链路枚举与绑定。
 *
 * 只用官方公开 API：`ConnectivityManager.allNetworks` + `NetworkCapabilities`，
 * 绑定用 `Network.socketFactory`（raw socket 绑定，TLS 在其上建立），
 * 不调用 `bindProcessToNetwork`，避免影响全进程其他请求。
 */
class AndroidLinkAggregator(
    private val context: Context,
    private val baseClient: OkHttpClient
) : LinkAggregator {

    private val networks = ConcurrentHashMap<String, Network>()

    override fun availableLinks(): List<NetworkLink> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
            ?: return emptyList()
        val found = mutableMapOf<String, Network>()
        val links = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return@mapNotNull null
            }
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return@mapNotNull null
            }
            val transport = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LinkTransport.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> LinkTransport.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LinkTransport.ETHERNET
                else -> return@mapNotNull null
            }
            val link = NetworkLink(
                id = "${transport.name.lowercase()}-${network.networkHandle}",
                transport = transport,
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                validated = true,
                networkHandle = network.networkHandle
            )
            found[link.id] = network
            link
        }.distinctBy { it.id }
        networks.putAll(found)
        return links
    }

    override fun bindRequest(link: NetworkLink): OkHttpClient {
        val network = networks[link.id] ?: return baseClient
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            // 每个链路独立连接池：链路之间不能复用 socket，同链路内仍可复用。
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .build()
    }
}
