package com.yadown.core.peer

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable
data class TrustedPeer(
    val fingerprint: String,
    val name: String,
    val pairedAt: Long
)

/**
 * TOFU 信任库。以证书指纹为键而不是 peerId：mDNS 的 serviceName 会随设备改名/重名后缀变化，
 * 只有指纹能稳定标识一台设备；指纹变化必须重新走配对流程。
 */
interface PeerTrustStore {
    fun isTrusted(fingerprint: String): Boolean
    fun get(fingerprint: String): TrustedPeer?
    fun all(): List<TrustedPeer>
    fun pair(fingerprint: String, name: String)
    fun unpair(fingerprint: String)
}

class FilePeerTrustStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis
) : PeerTrustStore {

    private val lock = Any()
    private var cache: MutableMap<String, TrustedPeer>? = null

    override fun isTrusted(fingerprint: String): Boolean = get(fingerprint) != null

    override fun get(fingerprint: String): TrustedPeer? = synchronized(lock) {
        entries()[normalize(fingerprint)]
    }

    override fun all(): List<TrustedPeer> = synchronized(lock) {
        entries().values.sortedByDescending { it.pairedAt }
    }

    override fun pair(fingerprint: String, name: String) {
        synchronized(lock) {
            val map = entries()
            val key = normalize(fingerprint)
            val existing = map[key]
            map[key] = TrustedPeer(
                fingerprint = key,
                name = name.trim().ifEmpty { existing?.name ?: key.take(8) },
                pairedAt = existing?.pairedAt ?: clock()
            )
            persist(map)
        }
    }

    override fun unpair(fingerprint: String) {
        synchronized(lock) {
            val map = entries()
            if (map.remove(normalize(fingerprint)) != null) persist(map)
        }
    }

    private fun entries(): MutableMap<String, TrustedPeer> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.isFile) emptyList()
            else PeerProtocol.json.decodeFromString<PeerTrustFile>(file.readText()).peers
        }.getOrDefault(emptyList())
        return loaded.associateBy { normalize(it.fingerprint) }.toMutableMap().also { cache = it }
    }

    private fun persist(map: MutableMap<String, TrustedPeer>) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(PeerProtocol.json.encodeToString(PeerTrustFile(peers = map.values.toList())))
        runCatching {
            java.io.FileOutputStream(temp, true).use { it.fd.sync() }
        }
        runCatching {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun normalize(fingerprint: String): String = fingerprint.trim().lowercase()

    @Serializable
    private data class PeerTrustFile(val version: Int = 1, val peers: List<TrustedPeer> = emptyList())
}
