package com.yadown.core.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.db.entity.PeerIndexEntity
import com.yadown.core.db.entity.SourceHealthEntity
import com.yadown.core.db.entity.TransferEventEntity
import com.yadown.core.transfer.ChunkState
import com.yadown.core.transfer.IntentState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDaoTest {

    private lateinit var db: YadownDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            YadownDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun intentAndChunkRoundTrip() = runBlocking {
        val intent = IntentEntity(
            id = "i1",
            contentId = "https://example.com/file",
            sourceUrl = "https://example.com/file",
            totalSize = 10,
            mimeType = "application/octet-stream",
            etag = "\"v1\"",
            lastModified = null,
            rangeSupported = true,
            valueMapJson = null,
            availabilityContractJson = null,
            state = IntentState.ACTIVE.name,
            createdAt = 1,
            updatedAt = 1
        )
        db.intentDao().upsert(intent)
        assertNotNull(db.intentDao().getById("i1"))
        assertEquals(IntentState.ACTIVE.name, db.intentDao().getById("i1")!!.state)

        val chunk = ChunkEntity(
            id = "i1-0",
            intentId = "i1",
            startByte = 0,
            endByte = 9,
            state = ChunkState.PENDING.name,
            retryCount = 0,
            checksum = null,
            connectionFingerprint = null,
            failureChainJson = null,
            startedAt = null,
            completedAt = null
        )
        db.chunkDao().upsert(chunk)

        val loaded = db.chunkDao().getByIntent("i1").single()
        assertEquals(0L, loaded.startByte)
        assertEquals(9L, loaded.endByte)

        db.chunkDao().updateProgress(
            id = chunk.id,
            state = ChunkState.DONE.name,
            retryCount = 0,
            startedAt = 1,
            completedAt = 2,
            checksum = 123L,
            failureChainJson = null
        )
        val done = db.chunkDao().getByIntent("i1").single()
        assertEquals(ChunkState.DONE.name, done.state)
        assertEquals(123L, done.checksum ?: -1L)

        db.transferEventDao().insert(
            TransferEventEntity(
                intentId = "i1",
                event = "COMPLETED",
                cause = null,
                bytes = 10,
                elapsedMs = 100,
                createdAt = 2
            )
        )
        assertEquals(1, db.transferEventDao().count())
        assertEquals("COMPLETED", db.transferEventDao().recent(1).single().event)
    }

    @Test
    fun sourceHealthAndPeerIndexRoundTrip() = runBlocking {
        val health = SourceHealthEntity(
            sourceKey = "cdn.example.com/a/b",
            ewmaThroughput = 1_000.0,
            ewmaFailureRate = 0.1,
            connectionLimitLearned = 3,
            lastUpdated = 100,
            ttlExpiresAt = 200,
            ewmaLatency = 45.0
        )
        db.sourceHealthDao().upsert(health)
        assertEquals(health, db.sourceHealthDao().get(health.sourceKey))
        assertEquals(listOf(health), db.sourceHealthDao().getValid(now = 150))
        assertTrue(db.sourceHealthDao().getValid(now = 250).isEmpty())

        val peer = PeerIndexEntity(
            peerId = "peer-1",
            ipAddress = "192.168.1.20",
            port = 8888,
            contentHashesJson = "h1,h2",
            lastSeenAt = 300,
            reliabilityScore = 0.5
        )
        db.peerIndexDao().upsert(peer)
        assertEquals(listOf("peer-1"), db.peerIndexDao().getSeenSince(300).map { it.peerId })
        db.peerIndexDao().deleteSeenBefore(301)
        assertTrue(db.peerIndexDao().getSeenSince(0).isEmpty())
    }
}
