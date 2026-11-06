package com.yadown.core.source

import com.yadown.core.db.dao.SourceHealthDao
import com.yadown.core.db.entity.SourceHealthEntity
import com.yadown.core.transfer.FailureCause
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoomSourceHealthRegistryTest {

    private class FakeDao : SourceHealthDao {
        val rows = mutableMapOf<String, SourceHealthEntity>()

        override suspend fun get(sourceKey: String): SourceHealthEntity? = rows[sourceKey]

        override suspend fun getValid(now: Long): List<SourceHealthEntity> =
            rows.values.filter { it.ttlExpiresAt > now }

        override suspend fun upsert(entity: SourceHealthEntity) {
            rows[entity.sourceKey] = entity
        }

        override suspend fun deleteExpired(now: Long) {
            rows.entries.removeIf { it.value.ttlExpiresAt <= now }
        }
    }

    private var now = 1_000L
    private val dao = FakeDao()
    private val registry = RoomSourceHealthRegistry(dao, SourceHealthModel(clock = { now }))

    @Test
    fun successAccumulatesEwmaAndExpandsLimit() = runBlocking {
        registry.recordSuccess("cdn/a", throughput = 1_000.0, latencyMs = 100.0)
        registry.recordSuccess("cdn/a", throughput = 2_000.0, latencyMs = 200.0)

        val health = registry.get("cdn/a")!!
        assertEquals(1_300.0, health.throughput, 1e-9)
        assertEquals(130.0, health.latencyMs, 1e-9)
        assertEquals(4, health.connectionLimit)
        assertEquals(4, registry.getConnectionLimit("cdn/a"))
    }

    @Test
    fun failureIsPersistedAndRollsBackLimit() = runBlocking {
        registry.recordSuccess("cdn/a", 1_000.0, 100.0)
        registry.recordFailure("cdn/a", FailureCause.NETWORK_TIMEOUT)

        val health = registry.get("cdn/a")!!
        assertEquals(0.3, health.failureRate, 1e-9)
        assertEquals(1, health.connectionLimit)
    }

    @Test
    fun nonSourceFailureDoesNotCreateRow() = runBlocking {
        registry.recordFailure("cdn/a", FailureCause.STORAGE_FULL)

        assertNull(registry.get("cdn/a"))
    }

    @Test
    fun expiredRowIsInvisible() = runBlocking {
        registry.recordSuccess("cdn/a", 1_000.0, 100.0)
        now += SourceHealthModel.DEFAULT_TTL_MS

        assertNull(registry.get("cdn/a"))
    }
}
