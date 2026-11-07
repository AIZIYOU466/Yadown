package com.yadown.core.peer

import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.source.SourceKind
import com.yadown.core.support.TestPeerIdentities
import com.yadown.core.support.testIntent
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerSourceProviderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val contentKey = ContentKey.of(SOURCE_URL)!!

    private class FakeDiscovery(private val peers: List<Peer>) : PeerDiscovery {
        override fun discover(): Flow<List<Peer>> = flowOf(peers)
        override fun queryContent(contentHash: String): List<Peer> =
            peers.filter { contentHash in it.contentHashes }
    }

    private class FakeManifests(private val manifests: Map<String, PeerManifest>) : PeerManifestSource {
        override suspend fun fetch(
            host: String,
            port: Int,
            contentKey: String,
            fingerprint: String
        ): PeerManifest? = manifests["$host:$port"]
    }

    private fun peer(
        host: String = "192.168.1.9",
        port: Int = 9000,
        fingerprint: String? = TestPeerIdentities.a.fingerprint
    ) = Peer(
        peerId = "peer-a",
        name = "客厅平板",
        host = host,
        port = port,
        contentHashes = setOf(contentKey),
        lastSeenAt = 0,
        fingerprint = fingerprint
    )

    private fun manifest(
        etag: String? = "\"v1\"",
        lastModified: String? = null,
        size: Long = 64,
        key: String = contentKey
    ) = PeerManifest(
        contentId = "peer-content-1",
        contentKey = key,
        size = size,
        etag = etag,
        lastModified = lastModified
    )

    private fun intent(): IntentEntity = testIntent(64, "\"v1\"")

    private fun provider(
        peers: List<Peer>,
        manifests: List<PeerManifest>,
        paired: Boolean = true
    ): Pair<PeerSourceProvider, PeerClientRegistry> {
        val trust = FilePeerTrustStore(File(tmp.root, "trust.json"))
        if (paired) trust.pair(TestPeerIdentities.a.fingerprint, "客厅平板")
        val registry = PeerClientRegistry()
        val provider = PeerSourceProvider(
            discovery = FakeDiscovery(peers),
            trust = trust,
            manifests = FakeManifests(manifests.associateBy { "192.168.1.9:9000" }),
            clients = registry
        )
        return provider to registry
    }

    @Test
    fun pairedPeerWithMatchingIdentityBecomesSource() = runBlocking {
        val (provider, _) = provider(listOf(peer()), listOf(manifest()))

        val endpoints = provider.endpointsFor(intent())

        assertEquals(1, endpoints.size)
        assertEquals(SourceKind.PEER, endpoints.first().kind)
        assertEquals(
            "https://192.168.1.9:9000/v1/content/peer-content-1",
            endpoints.first().url
        )
    }

    @Test
    fun unpairedPeerIsSkipped() = runBlocking {
        val (provider, _) = provider(listOf(peer()), listOf(manifest()), paired = false)

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    @Test
    fun etagMismatchIsRejected() = runBlocking {
        val (provider, _) = provider(listOf(peer()), listOf(manifest(etag = "\"other\"")))

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    @Test
    fun sizeMismatchIsRejected() = runBlocking {
        val (provider, _) = provider(listOf(peer()), listOf(manifest(size = 128)))

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    @Test
    fun contentKeyMismatchIsRejected() = runBlocking {
        val (provider, _) = provider(
            listOf(peer()),
            listOf(manifest(key = "ffffffffffffffff"))
        )

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    @Test
    fun weakEtagFallsBackToLastModified() = runBlocking {
        val weakIntent = intent().copy(etag = "W/\"v1\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT")
        val (provider, _) = provider(
            listOf(peer()),
            listOf(manifest(etag = "\"v1\"", lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"))
        )

        assertEquals(1, provider.endpointsFor(weakIntent).size)
    }

    @Test
    fun intentWithoutValidatorsIsSkippedEntirely() = runBlocking {
        val (provider, _) = provider(listOf(peer()), listOf(manifest()))
        val noValidators = intent().copy(etag = null, lastModified = null)

        assertTrue(provider.endpointsFor(noValidators).isEmpty())
    }

    @Test
    fun missingManifestIsIgnored() = runBlocking {
        val (provider, _) = provider(listOf(peer()), emptyList())

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    @Test
    fun uncheckedPeerWithoutFingerprintIsSkipped() = runBlocking {
        val (provider, _) = provider(listOf(peer(fingerprint = null)), listOf(manifest()))

        assertTrue(provider.endpointsFor(intent()).isEmpty())
    }

    private companion object {
        const val SOURCE_URL = "https://example.com/file"
    }
}
