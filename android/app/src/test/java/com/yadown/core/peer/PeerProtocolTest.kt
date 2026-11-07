package com.yadown.core.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerProtocolTest {

    @Test
    fun contentKeyNormalizesSchemeHostAndDefaultPort() {
        val a = ContentKey.of("https://Example.COM:443/a/b.mp4?x=1")
        val b = ContentKey.of("https://example.com/a/b.mp4?x=1")
        val c = ContentKey.of("http://example.com:80/a/b.mp4?x=1")

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertEquals(16, a!!.length)
        assertEquals(a, a.lowercase())
    }

    @Test
    fun contentKeyKeepsQueryAndRejectsUnsupportedUrls() {
        assertNotEquals(
            ContentKey.of("https://example.com/f?token=a"),
            ContentKey.of("https://example.com/f?token=b")
        )
        assertNull(ContentKey.of("ftp://example.com/f"))
        assertNull(ContentKey.of("https://user:pass@example.com/f"))
        assertNull(ContentKey.of("not a url"))
    }

    @Test
    fun pathsParseOnlyProtocolShape() {
        assertEquals(
            PeerRequest.Lookup("0123456789abcdef"),
            PeerPaths.parse("/v1/lookup/0123456789abcdef")
        )
        assertEquals(
            PeerRequest.Content("0f0e0d0c-0b0a-0908"),
            PeerPaths.parse("/v1/content/0f0e0d0c-0b0a-0908")
        )
        assertNull(PeerPaths.parse("/v1/lookup/XYZ"))
        assertNull(PeerPaths.parse("/v1/content/../../etc/passwd"))
        assertNull(PeerPaths.parse("/v1/lookup/0123456789abcdef/extra"))
    }

    @Test
    fun urlsBracketIpv6AndStripScope() {
        assertEquals(
            "https://[fe80::1]:9000/v1/content/id",
            PeerUrls.content("fe80::1%wlan0", 9000, "id")
        )
        assertEquals(
            "https://192.168.1.5:9000/v1/lookup/0123456789abcdef",
            PeerUrls.lookup("192.168.1.5", 9000, "0123456789abcdef")
        )
    }
}
