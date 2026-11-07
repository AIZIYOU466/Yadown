package com.yadown.core.peer

import com.yadown.core.support.TestPeerIdentities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerCertificateTest {

    @Test
    fun fingerprintsAreStableAndDistinct() {
        val a = TestPeerIdentities.a.fingerprint
        val b = TestPeerIdentities.b.fingerprint

        assertEquals(64, a.length)
        assertEquals(a, PeerCertificate.fingerprint(TestPeerIdentities.a.certificate))
        assertNotEquals(a, b)
    }

    @Test
    fun shortCodeIsSixDigitsDerivedFromFingerprintHead() {
        val shortCode = TestPeerIdentities.a.shortCode

        assertEquals(6, shortCode.length)
        assertTrue(shortCode.all { it.isDigit() })
        assertEquals(shortCode, PeerCertificate.shortCode(TestPeerIdentities.a.fingerprint))
    }

    @Test
    fun shortCodeZeroPadded() {
        assertEquals("000000", PeerCertificate.shortCode("00000000ffffffff"))
        assertEquals("000001", PeerCertificate.shortCode("0000000100000000"))
    }
}
