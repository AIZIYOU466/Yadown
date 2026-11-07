package com.yadown.core.peer

import com.yadown.core.support.TestPeerIdentities
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PeerTrustStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1_000L

    private fun store(): FilePeerTrustStore =
        FilePeerTrustStore(File(tmp.root, "peer_trust.json")) { now }

    @Test
    fun pairPersistsAcrossInstancesAndUnpairRemoves() {
        val fingerprint = TestPeerIdentities.a.fingerprint
        val first = store()
        first.pair(fingerprint, "客厅平板")
        assertTrue(first.isTrusted(fingerprint))

        val reopened = store()
        assertTrue(reopened.isTrusted(fingerprint))
        assertEquals("客厅平板", reopened.get(fingerprint)?.name)

        reopened.unpair(fingerprint)
        assertFalse(store().isTrusted(fingerprint))
    }

    @Test
    fun fingerprintIsNormalizedAndPairIsIdempotent() {
        val store = store()
        val fingerprint = TestPeerIdentities.a.fingerprint
        store.pair(fingerprint.uppercase(), "A")
        now = 2_000L
        store.pair(fingerprint, "B")

        assertEquals(1, store.all().size)
        assertEquals(1_000L, store.get(fingerprint)?.pairedAt)
        assertEquals("B", store.get(fingerprint)?.name)
    }

    @Test
    fun corruptFileFallsBackToEmptyTrust() {
        val file = File(tmp.root, "peer_trust.json")
        file.parentFile?.mkdirs()
        file.writeText("{broken")

        val store = FilePeerTrustStore(file)
        assertEquals(0, store.all().size)
        assertNull(store.get("anything"))

        store.pair("abcd", "x")
        assertTrue(FilePeerTrustStore(file).isTrusted("abcd"))
    }

    @Test
    fun concurrentPairsDoNotLoseEntries() {
        val store = store()
        val threads = (0 until 8).map { index ->
            Thread { store.pair("%064x".format(index), "p$index") }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(8, store.all().size)
        assertNotNull(store.get("%064x".format(7)))
    }
}
