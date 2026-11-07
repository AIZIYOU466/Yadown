package com.yadown.core.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkAllocatorTest {

    private fun link(
        transport: LinkTransport,
        metered: Boolean = false,
        validated: Boolean = true
    ) = NetworkLink(
        id = "${transport.name.lowercase()}-1",
        transport = transport,
        metered = metered,
        validated = validated
    )

    @Test
    fun privateAddressesAreDetected() {
        listOf(
            "http://192.168.1.5/f",
            "http://10.0.0.1/f",
            "http://172.16.0.1/f",
            "http://172.31.255.1/f",
            "http://169.254.1.1/f",
            "http://127.0.0.1/f",
            "http://localhost/f",
            "http://nas/f",
            "http://nas.local/f",
            "https://[fd00::1]/f",
            "https://[fe80::1%25wlan0]/f"
        ).forEach { url ->
            assertTrue("应识别为局域网: $url", LinkAllocator.isLocalOrPrivate(url))
        }
    }

    @Test
    fun publicAddressesAreNotPrivate() {
        listOf(
            "https://cdn.example.com/f",
            "https://1.1.1.1/f",
            "https://172.32.0.1/f",
            "https://[2001:db8::1]/f"
        ).forEach { url ->
            assertFalse("应识别为公网: $url", LinkAllocator.isLocalOrPrivate(url))
        }
    }

    @Test
    fun cellularIsRejectedForPrivateTargets() {
        assertFalse(
            LinkAllocator.allowed("http://192.168.1.5/f", link(LinkTransport.CELLULAR, metered = true), true)
        )
        assertTrue(
            LinkAllocator.allowed("http://192.168.1.5/f", link(LinkTransport.WIFI), true)
        )
    }

    @Test
    fun meteredLinkNeedsOptIn() {
        val cellular = link(LinkTransport.CELLULAR, metered = true)
        assertTrue(LinkAllocator.allowed("https://cdn.example.com/f", cellular, true))
        assertFalse(LinkAllocator.allowed("https://cdn.example.com/f", cellular, false))
    }

    @Test
    fun unvalidatedLinkIsRejected() {
        assertFalse(
            LinkAllocator.allowed(
                "https://cdn.example.com/f",
                link(LinkTransport.WIFI, validated = false),
                true
            )
        )
    }
}
