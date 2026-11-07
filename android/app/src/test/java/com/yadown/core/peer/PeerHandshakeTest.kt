package com.yadown.core.peer

import com.yadown.core.support.TestPeerIdentities
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerHandshakeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: PeerServer

    @Before
    fun setUp() {
        val content = SharedContent(
            contentId = "handshake-1",
            contentKey = ContentKey.of("https://example.com/f")!!,
            size = 8,
            etag = "\"v1\"",
            lastModified = null,
            fileName = "f.bin",
            mimeType = null,
            file = File(tmp.root, "f.bin").apply { writeBytes(ByteArray(8)) }
        )
        server = PeerServer(TestPeerIdentities.a, contents = { listOf(content) }).also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun probeReturnsActualCertificateAndShortCode() = runBlocking {
        val result = PeerHandshake().probe("127.0.0.1", server.port)

        assertNotNull(result)
        assertEquals(TestPeerIdentities.a.fingerprint, result!!.fingerprint)
        assertEquals(TestPeerIdentities.a.shortCode, result.shortCode)
        assertEquals("127.0.0.1", result.host)
    }

    @Test
    fun probeOfClosedPortReturnsNull() = runBlocking {
        val port = server.port
        server.close()
        assertNull(PeerHandshake().probe("127.0.0.1", port))
    }
}
