package com.yadown.core.peer

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PeerRow(val peer: Peer, val trusted: Boolean)

data class PairCandidate(
    val host: String,
    val port: Int,
    val name: String,
    val fingerprint: String,
    val shortCode: String
)

/**
 * 局域网加速的运行时：按用户开关启动 TLS 服务端、注册 mDNS、保持发现流，
 * 并维护配对状态。所有网络与磁盘操作都在 IO 作用域内，UI 只读 [state]。
 */
@Singleton
class PeerRuntime @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: PeerShareSettings,
    private val shares: PeerShareRepository,
    private val trust: PeerTrustStore,
    private val discovery: PeerDiscovery,
    private val clients: PeerClientRegistry,
    private val handshake: PeerHandshake
) {

    data class State(
        val enabled: Boolean = false,
        val running: Boolean = false,
        val identityAvailable: Boolean = false,
        val deviceName: String = "",
        val shortCode: String? = null,
        val sharedCount: Int = 0,
        val peers: List<PeerRow> = emptyList(),
        val error: String? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val advertiser = NsdAdvertiser(context)

    private val _state = MutableStateFlow(
        State(enabled = settings.enabled, deviceName = settings.deviceName)
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var identity: AndroidPeerIdentity? = null
    private var server: PeerServer? = null
    private var discoveryJob: Job? = null
    private var shareJob: Job? = null

    @Volatile
    private var cached: List<SharedContent> = emptyList()

    fun restore() {
        if (settings.enabled) start()
    }

    fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        if (enabled) start() else stop()
    }

    private fun start() {
        scope.launch {
            mutex.withLock {
                if (server != null) return@withLock
                val loaded = identity
                    ?: runCatching { AndroidPeerIdentity.loadOrCreate() }.getOrNull()
                if (loaded == null) {
                    settings.setEnabled(false)
                    _state.update {
                        it.copy(
                            enabled = false,
                            running = false,
                            identityAvailable = false,
                            error = "本机证书生成失败，局域网加速不可用"
                        )
                    }
                    return@withLock
                }
                identity = loaded
                val contents = runCatching { shares.contents() }.getOrDefault(emptyList())
                cached = contents
                val created = PeerServer(loaded, contents = { cached })
                created.start()
                server = created
                advertiser.register(
                    port = created.port,
                    name = settings.deviceName,
                    fingerprint = loaded.fingerprint,
                    keys = contents.map { it.contentKey }
                )
                _state.update {
                    it.copy(
                        enabled = true,
                        running = true,
                        identityAvailable = true,
                        deviceName = settings.deviceName,
                        shortCode = loaded.shortCode,
                        sharedCount = contents.size,
                        error = null
                    )
                }
                startDiscovery()
                watchShares()
            }
        }
    }

    private fun stop() {
        scope.launch {
            mutex.withLock {
                discoveryJob?.cancel()
                discoveryJob?.join()
                discoveryJob = null
                shareJob?.cancel()
                shareJob?.join()
                shareJob = null
                advertiser.unregister()
                server?.close()
                server = null
                cached = emptyList()
                _state.update {
                    it.copy(
                        enabled = false,
                        running = false,
                        shortCode = null,
                        sharedCount = 0,
                        peers = emptyList()
                    )
                }
            }
        }
    }

    private fun startDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            discovery.discover()
                .catch { throwable ->
                    _state.update { it.copy(error = throwable.message ?: "局域网发现失败") }
                }
                .collect { peers -> _state.update { it.copy(peers = rows(peers)) } }
        }
    }

    @OptIn(FlowPreview::class)
    private fun watchShares() {
        shareJob?.cancel()
        shareJob = scope.launch {
            shares.observeContents()
                .debounce(1_500)
                .collect { contents ->
                    cached = contents
                    _state.update { it.copy(sharedCount = contents.size) }
                    val current = identity ?: return@collect
                    val port = server?.port ?: return@collect
                    advertiser.register(
                        port = port,
                        name = settings.deviceName,
                        fingerprint = current.fingerprint,
                        keys = contents.map { it.contentKey }
                    )
                }
        }
    }

    private fun rows(peers: List<Peer>): List<PeerRow> = peers.map { peer ->
        PeerRow(peer, peer.fingerprint?.let { trust.isTrusted(it) } == true)
    }

    /** 只做一次 TLS 握手读取对端真实证书，供用户比对短码。 */
    suspend fun probe(host: String, port: Int, name: String?): PairCandidate? {
        val result = handshake.probe(host, port) ?: return null
        return PairCandidate(
            host = host,
            port = port,
            name = name?.takeIf { it.isNotBlank() } ?: "$host:$port",
            fingerprint = result.fingerprint,
            shortCode = result.shortCode
        )
    }

    fun confirmPair(candidate: PairCandidate) {
        trust.pair(candidate.fingerprint, candidate.name)
        clients.trust(candidate.host, candidate.port, candidate.fingerprint)
        refreshPeerRows()
    }

    fun unpair(fingerprint: String) {
        trust.unpair(fingerprint)
        _state.value.peers
            .filter { it.peer.fingerprint == fingerprint }
            .forEach { clients.forget(it.peer.host, it.peer.port) }
        refreshPeerRows()
    }

    private fun refreshPeerRows() {
        _state.update { it.copy(peers = rows(it.peers.map { row -> row.peer })) }
    }

    companion object {
        /** 手动配对输入：`192.168.1.5:9000` 或 `[fe80::1]:9000`。 */
        fun parseAddress(text: String): Pair<String, Int>? {
            val trimmed = text.trim()
            val separator = trimmed.lastIndexOf(':')
            if (separator <= 0) return null
            val host = trimmed.substring(0, separator).trim().removeSurrounding("[", "]")
            val port = trimmed.substring(separator + 1).trim().toIntOrNull() ?: return null
            if (host.isBlank() || port !in 1..65_535) return null
            return host to port
        }
    }
}

/**
 * mDNS 服务注册。重新注册前必须反注册，否则部分 ROM 会返回 FAILURE_ALREADY_ACTIVE；
 * TXT 属性受 key+value < 255 字节限制，因此只广播最近 4 个内容键。
 */
private class NsdAdvertiser(private val context: Context) {

    private val mutex = Mutex()
    private var listener: NsdManager.RegistrationListener? = null

    suspend fun register(port: Int, name: String, fingerprint: String, keys: List<String>) {
        if (port <= 0) return
        mutex.withLock {
            unregisterLocked()
            val manager = context.getSystemService(NsdManager::class.java) ?: return
            val info = NsdServiceInfo().apply {
                serviceName = "yadown-${fingerprint.take(6)}"
                serviceType = NsdPeerDiscovery.SERVICE_TYPE
                this.port = port
                setAttribute("name", name.take(63))
                setAttribute("fp", fingerprint)
                setAttribute("keys", keys.take(MAX_ADVERTISED_KEYS).joinToString(","))
            }
            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            listener = registration
            runCatching {
                manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            }.onFailure { listener = null }
        }
    }

    suspend fun unregister() {
        mutex.withLock { unregisterLocked() }
    }

    private fun unregisterLocked() {
        val current = listener ?: return
        listener = null
        val manager = context.getSystemService(NsdManager::class.java) ?: return
        runCatching { manager.unregisterService(current) }
    }

    private companion object {
        const val MAX_ADVERTISED_KEYS = 4
    }
}
