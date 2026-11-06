package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceHealth
import com.yadown.core.source.SourceHealthRegistry
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.RoutingRangeSource
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MultiSourceDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class RecordingRegistry : SourceHealthRegistry {
        val successes = mutableListOf<String>()
        val failures = mutableListOf<Pair<String, FailureCause>>()

        override suspend fun recordSuccess(sourceKey: String, throughput: Double, latencyMs: Double) {
            successes += sourceKey
        }

        override suspend fun recordFailure(sourceKey: String, cause: FailureCause) {
            failures += sourceKey to cause
        }

        override suspend fun get(sourceKey: String): SourceHealth? = null

        override suspend fun getConnectionLimit(sourceKey: String): Int? = null
    }

    private fun route(url: String, kind: SourceKind = SourceKind.ORIGIN) = SourceRoute(
        endpoint = SourceEndpoint(url, kind),
        score = 1.0,
        reliability = 1.0,
        throughputScore = 1.0,
        connectionLimit = 2,
        usable = true
    )

    private fun engine(
        source: RoutingRangeSource,
        bitmapStore: BitmapStore,
        intentDao: FakeIntentDao,
        chunkDao: FakeChunkDao,
        registry: SourceHealthRegistry? = null
    ) = DownloadEngine(
        source = source,
        files = RandomAccessFileStore(),
        bitmaps = bitmapStore,
        intentDao = intentDao,
        chunkDao = chunkDao,
        ioDispatcher = Dispatchers.Unconfined,
        healthRegistry = registry
    )

    @Test
    fun spreadsChunksAcrossVerifiedSourcesAndCompletes() = runTest {
        val total = 16L
        val chunkSize = 4L
        val a = "https://a.example.com/f"
        val b = "https://b.example.com/f"
        val source = RoutingRangeSource(total)
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        val registry = RecordingRegistry()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val file = tmp.newFile()

        engine(source, InMemoryBitmapStore(), intentDao, chunkDao, registry).download(
            intentId = "i1",
            url = a,
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = chunkSize,
            routes = listOf(route(a), route(b, SourceKind.MIRROR))
        )

        assertEquals((0 until total).map { it.toByte() }, file.readBytes().toList())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("主源应收到请求", source.requests.any { it.first == a })
        assertTrue("镜像应收到请求", source.requests.any { it.first == b })
        assertTrue("两个源都应回写健康度", registry.successes.toSet().size == 2)
    }

    @Test
    fun failedChunkRetriesOnAnotherSource() = runTest {
        val total = 16L
        val chunkSize = 4L
        val a = "https://a.example.com/f"
        val b = "https://b.example.com/f"
        val source = RoutingRangeSource(total).apply { failOnce(a) }
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        val registry = RecordingRegistry()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val file = tmp.newFile()

        engine(source, InMemoryBitmapStore(), intentDao, chunkDao, registry).download(
            intentId = "i1",
            url = a,
            destPath = file.absolutePath,
            concurrency = 2,
            chunkSize = chunkSize,
            routes = listOf(route(a), route(b, SourceKind.MIRROR))
        )

        assertEquals((0 until total).map { it.toByte() }, file.readBytes().toList())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("失败后应切换到另一源重试", source.requests.any { it.first == b })
        assertTrue(
            "失败应归因到原源",
            registry.failures.any { it.first == "a.example.com/f" }
        )
    }
}
