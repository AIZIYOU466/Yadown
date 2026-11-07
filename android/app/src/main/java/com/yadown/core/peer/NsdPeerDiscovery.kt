package com.yadown.core.peer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.yadown.core.db.dao.PeerIndexDao
import com.yadown.core.db.entity.PeerIndexEntity
import java.net.Inet4Address
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T4.2：用官方 NsdManager 发现同一局域网内的 Yadown 设备，只建立索引，不传输数据。
 *
 * `resolveService` 不能并发调用（会返回 FAILURE_ALREADY_ACTIVE），因此发现回调只入队，
 * 由单个协程串行解析；解析超时的服务直接丢弃，不阻塞后续设备。
 *
 * 本地网络权限：targetSdk 36 时本地网络访问由 INTERNET 隐式授予，不要声明
 * ACCESS_LOCAL_NETWORK；该权限从 targetSdk 37 起才强制（Android 官方
 * local-network-permission 文档，2026-10）。
 */
class NsdPeerDiscovery(
    private val context: Context,
    private val dao: PeerIndexDao,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerDiscovery {

    private val index = PeerIndex(clock)

    override fun discover(): Flow<List<Peer>> = callbackFlow {
        val nsd = context.getSystemService(NsdManager::class.java)
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolver = launch(Dispatchers.IO) {
            for (info in resolveQueue) {
                val resolved = resolveWithTimeout(nsd, info) ?: continue
                val peer = toPeer(resolved) ?: continue
                index.upsert(peer)
                runCatching { persist(peer) }
                trySend(index.snapshot())
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IllegalStateException("mDNS 发现启动失败: $errorCode"))
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolveQueue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                index.remove(serviceInfo.serviceName)
                trySend(index.snapshot())
                launch { runCatching { dao.delete(serviceInfo.serviceName) } }
            }
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        trySend(index.snapshot())

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            resolveQueue.close()
            resolver.cancel()
        }
    }

    override fun queryContent(contentHash: String): List<Peer> =
        index.queryContent(contentHash)

    private suspend fun resolveWithTimeout(
        nsd: NsdManager,
        info: NsdServiceInfo
    ): NsdServiceInfo? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    continuation.resume(null)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    continuation.resume(serviceInfo)
                }
            })
        }
    }

    private fun toPeer(info: NsdServiceInfo): Peer? {
        val host = hostOf(info) ?: return null
        if (info.port <= 0) return null
        val attributes = info.attributes.orEmpty()
        val contentHashes = attributes["keys"]
            ?.let { String(it, StandardCharsets.UTF_8) }
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()
        val fingerprint = attributes["fp"]
            ?.let { String(it, StandardCharsets.UTF_8).trim().lowercase() }
            ?.takeIf { it.length == 64 }
        val name = attributes["name"]
            ?.let { String(it, StandardCharsets.UTF_8).trim() }
            ?.takeIf { it.isNotEmpty() }
            ?: info.serviceName
        return Peer(
            peerId = info.serviceName,
            name = name,
            host = host,
            port = info.port,
            contentHashes = contentHashes,
            lastSeenAt = clock(),
            fingerprint = fingerprint
        )
    }

    /** 局域网内优先用 IPv4：IPv6 链路本地地址在 URL 中不可直接使用。 */
    private fun hostOf(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        return addresses.firstOrNull { it is Inet4Address }?.hostAddress
            ?: addresses.firstOrNull()?.hostAddress
    }

    private suspend fun persist(peer: Peer) {
        dao.upsert(
            PeerIndexEntity(
                peerId = peer.peerId,
                ipAddress = peer.host,
                port = peer.port,
                contentHashesJson = peer.contentHashes.joinToString(","),
                lastSeenAt = peer.lastSeenAt,
                reliabilityScore = peer.reliability
            )
        )
    }

    companion object {
        const val SERVICE_TYPE = "_yadown._tcp"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
    }
}
