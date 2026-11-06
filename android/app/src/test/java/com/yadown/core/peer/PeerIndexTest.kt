package com.yadown.core.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerIndexTest {

    private var now = 10_000L
    private val index = PeerIndex(clock = { now }, ttlMs = 1_000)

    private fun peer(
        id: String,
        name: String = id,
        hashes: Set<String> = emptySet(),
        reliability: Double = 0.5
    ) = Peer(
        peerId = id,
        name = name,
        host = "192.168.1.10",
        port = 8888,
        contentHashes = hashes,
        lastSeenAt = now,
        reliability = reliability
    )

    @Test
    fun snapshotSortsByReliabilityThenFreshness() {
        index.upsert(peer("low", reliability = 0.4))
        index.upsert(peer("high", reliability = 0.9))
        index.upsert(peer("mid", reliability = 0.5))

        assertEquals(listOf("high", "mid", "low"), index.snapshot().map { it.peerId })
    }

    @Test
    fun expiredPeerDisappearsFromSnapshot() {
        index.upsert(peer("old"))
        now += 1_001

        assertTrue(index.snapshot().isEmpty())
    }

    @Test
    fun queryContentMatchesOnlyPeersDeclaringThatHash() {
        index.upsert(peer("a", hashes = setOf("h1", "h2")))
        index.upsert(peer("b", hashes = setOf("h2")))

        assertEquals(listOf("a"), index.queryContent("h1").map { it.peerId })
        assertEquals(2, index.queryContent("h2").size)
        assertTrue(index.queryContent("h3").isEmpty())
    }

    @Test
    fun removeDeletesPeer() {
        index.upsert(peer("a"))

        index.remove("a")

        assertTrue(index.snapshot().isEmpty())
    }
}
