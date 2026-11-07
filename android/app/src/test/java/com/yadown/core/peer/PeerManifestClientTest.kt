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

class PeerManifestClientTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: PeerServer
    private lateinit var content: SharedContent

    @Before
    fun setUp() {
        content = SharedContent(
            contentId = "manifest-1",
            contentKey = ContentKey.of(SOURCE_URL)!!,
            size = 32,
            etag = "\"v1\"",
            lastModified = null,
            fileName = "f.bin",
            mimeType = "application/octet-stream",
            file = File(tmp.root, "f.bin").apply { writeBytes(ByteArray(32)) }
        )
        server = PeerServer(TestPeerIdentities.a, contents = { listOf(content) }).also { it.start() }
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun fetchReturnsManifestForPinnedPeer() = runBlocking {
        val manifest = PeerManifestClient().fetch(
            "127.0.0.1",
            server.port,
            content.contentKey,
            TestPeerIdentities.a.fingerprint
        )

        assertNotNull(manifest)
        assertEquals(content.contentId, manifest!!.contentId)
        assertEquals(content.size, manifest.size)
        assertEquals(content.etag, manifest.etag)
    }

    @Test
    fun fetchFailsForDifferentFingerprint() = runBlocking {
        val manifest = PeerManifestClient().fetch(
            "127.0.0.1",
            server.port,
            content.contentKey,
            TestPeerIdentities.b.fingerprint
        )

        assertNull(manifest)
    }

    @Test
    fun fetchReturnsNullForUnknownKey() = runBlocking {
        val manifest = PeerManifestClient().fetch(
            "127.0.0.1",
            server.port,
            "0123456789abcdef",
            TestPeerIdentities.a.fingerprint
        )

        assertNull(manifest)
    }

    private companion object {
        const val SOURCE_URL = "https://example.com/file"
    }
}
