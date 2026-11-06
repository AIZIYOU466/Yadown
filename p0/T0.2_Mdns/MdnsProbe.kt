package com.yadown.p0.mdns

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * T0.2 mDNS 可靠性探针。
 *
 * 假设：家庭/办公网络下 mDNS peer 发现率可用（>80%）。
 * 依赖官方 NsdManager API：registerService / discoverServices / resolveService。
 */
object MdnsProbe {

    const val SERVICE_TYPE = "_yadown._tcp"

    fun registerService(
        context: Context,
        serviceName: String,
        port: Int,
        onRegistered: () -> Unit = {},
        onFailed: (Int) -> Unit = {}
    ): NsdManager.RegistrationListener {
        val nsd = context.getSystemService(NsdManager::class.java)
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = onRegistered()
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = onFailed(errorCode)
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    fun unregister(context: Context, listener: NsdManager.RegistrationListener) {
        context.getSystemService(NsdManager::class.java).unregisterService(listener)
    }

    /**
     * 发起发现并解析服务，记录每次发现的耗时（毫秒）。
     * 调用方负责在开始前设置计时起点；返回的 listener 可传给 [stopDiscovery]。
     */
    fun discoverServices(
        context: Context,
        onResolved: (NsdServiceInfo, elapsedMs: Long) -> Unit,
        onFailed: (Int) -> Unit = {}
    ): NsdManager.DiscoveryListener {
        val nsd = context.getSystemService(NsdManager::class.java)
        val startNs = System.nanoTime()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = onFailed(errorCode)
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = onFailed(errorCode)
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = onFailed(errorCode)
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
                        onResolved(info, elapsedMs)
                    }
                })
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        return listener
    }

    fun stopDiscovery(context: Context, listener: NsdManager.DiscoveryListener) {
        context.getSystemService(NsdManager::class.java).stopServiceDiscovery(listener)
    }
}