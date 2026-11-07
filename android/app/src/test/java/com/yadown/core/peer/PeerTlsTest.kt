package com.yadown.core.peer

import com.yadown.core.support.TestPeerIdentities
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerTlsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: PeerServer

    @Before
    fun setUp() {
        val file = tmp.newFile("f.bin").apply { writeBytes(ByteArray(16)) }
        val content = SharedContent(
            contentId = "tls-content",
            contentKey = ContentKey.of("https://example.com/f")!!,
            size = 16,
            etag = "\"v1\"",
            lastModified = null,
            fileName = "f.bin",
            mimeType = "application/octet-stream",
            file = file
        )
        server = PeerServer(TestPeerIdentities.a, contents = { listOf(content) }).also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun pinnedClientHandshakesWithMatchingCertificate() = runBlocking {
        val handshake = PeerHandshake({ PeerTls.clientTlsForFingerprint(TestPeerIdentities.a.fingerprint) })
        val result = handshake.probe("127.0.0.1", server.port)

        assertNotNull(result)
        assertEquals(TestPeerIdentities.a.fingerprint, result!!.fingerprint)
        assertEquals(TestPeerIdentities.a.shortCode, result.shortCode)
    }

    @Test
    fun pinnedClientRejectsDifferentCertificate() = runBlocking {
        val handshake = PeerHandshake({ PeerTls.clientTlsForFingerprint(TestPeerIdentities.b.fingerprint) })
        assertNull(handshake.probe("127.0.0.1", server.port))
    }

    @Test
    fun captureTlsReadsRealCertificate() = runBlocking {
        val result = PeerHandshake().probe("127.0.0.1", server.port)

        assertNotNull(result)
        assertEquals(TestPeerIdentities.a.fingerprint, result!!.fingerprint)
    }
}
