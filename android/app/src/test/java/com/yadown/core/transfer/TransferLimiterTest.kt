package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TransferLimiterTest {

    @Test
    fun keyIsHostWithoutLinkAndHostWithLink() {
        assertEquals("a.example.com", TransferLimiter.keyOf("https://a.example.com/f", null))
        assertEquals("a.example.com", TransferLimiter.keyOf("https://a.example.com/f", " "))
        assertEquals("a.example.com@wifi", TransferLimiter.keyOf("https://a.example.com/f", "wifi"))
    }

    @Test
    fun differentLinksHaveIndependentKeys() {
        val url = "https://a.example.com/f"
        assertNotEquals(
            TransferLimiter.keyOf(url, "wifi"),
            TransferLimiter.keyOf(url, "cellular")
        )
    }
}
