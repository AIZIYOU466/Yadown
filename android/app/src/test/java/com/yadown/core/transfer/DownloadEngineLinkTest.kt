package com.yadown.core.transfer

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.LinkHealthStore
import com.yadown.core.net.MultiLinkRangeSource
import com.yadown.core.net.ProbeResult
import com.yadown.core.net.RangePayload
import com.yadown.core.net.RangeSource
import com.yadown.core.source.SourceEndpoint
import com.yadown.core.source.SourceKind
import com.yadown.core.source.SourceRoute
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T4.5：两条链路各自承接分块，真实下载引擎完成下载。 */
class DownloadEngineLinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val total = 16L
    private val chunkSize = 4L
    private val url = "https://example.com/file"

    private class LinkSource(private val total: Long, private val failFirst: Boolean) : RangeSource {
        private val count = AtomicInteger()

        override suspend fun probe(url: String, linkId: String?) =
            ProbeResult(total, true, "\"v1\"", null, null)

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            val call = count.incrementAndGet()
            if (failFirst && call == 1) throw IOException("链路故障")
            val bytes = ByteArray((endInclusive - start + 1).toInt()) { offset ->
                ((start + offset) % 251).toByte()
            }
            onProgress(bytes.size.toLong())
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/$total",
                etag = "\"v1\"",
                lastModified = null
            )
        }

        fun calls(): Int = count.get()
    }

    private fun route(linkId: String?) = SourceRoute(
        endpoint = SourceEndpoint(url, SourceKind.ORIGIN, linkId = linkId),
        score = 1.0,
        reliability = 1.0,
        throughputScore = 1.0,
        connectionLimit = 2,
        usable = true
    )

    private fun engine(
        source: RangeSource,
        intentDao: FakeIntentDao,
        chunkDao: FakeChunkDao
    ) = DownloadEngine(
        source = source,
        files = RandomAccessFileStore(),
        bitmaps = InMemoryBitmapStore(),
        intentDao = intentDao,
        chunkDao = chunkDao,
        ioDispatcher = Dispatchers.Unconfined
    )

    private fun multiLink(wifi: RangeSource, cellular: RangeSource, health: LinkHealthStore) =
        MultiLinkRangeSource(
            delegate = LinkSource(total, failFirst = false),
            linkSources = { id ->
                when (id) {
                    "wifi" -> wifi
                    "cellular" -> cellular
                    else -> null
                }
            },
            health = health
        )

    @Test
    fun chunksAreSpreadAcrossTwoLinks() = runBlocking {
        val wifi = LinkSource(total, failFirst = false)
        val cellular = LinkSource(total, failFirst = false)
        val health = LinkHealthStore()
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val dest = tmp.newFile()

        engine(multiLink(wifi, cellular, health), intentDao, chunkDao).download(
            intentId = "i1",
            url = url,
            destPath = dest.absolutePath,
            concurrency = 2,
            chunkSize = chunkSize,
            routes = listOf(route("wifi"), route("cellular"))
        )

        val expected = (0 until total.toInt()).map { (it % 251).toByte() }
        assertArrayEquals(expected.toByteArray(), dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("Wi-Fi 应承接分块", wifi.calls() > 0)
        assertTrue("蜂窝应承接分块", cellular.calls() > 0)
        assertTrue(health.get("wifi")!!.throughput > 0)
        assertTrue(health.get("cellular")!!.throughput > 0)
    }

    @Test
    fun failedLinkRetriesOnAnotherLink() = runBlocking {
        val wifi = LinkSource(total, failFirst = true)
        val cellular = LinkSource(total, failFirst = false)
        val health = LinkHealthStore()
        val intentDao = FakeIntentDao()
        val chunkDao = FakeChunkDao()
        intentDao.upsert(testIntent(total, "\"v1\""))
        seedTestChunks(chunkDao, total, chunkSize)
        val dest = tmp.newFile()

        engine(multiLink(wifi, cellular, health), intentDao, chunkDao).download(
            intentId = "i1",
            url = url,
            destPath = dest.absolutePath,
            concurrency = 1,
            chunkSize = chunkSize,
            routes = listOf(route("wifi"), route("cellular"))
        )

        val expected = (0 until total.toInt()).map { (it % 251).toByte() }
        assertArrayEquals(expected.toByteArray(), dest.readBytes())
        assertEquals(IntentState.COMPLETED.name, intentDao.getById("i1")!!.state)
        assertTrue("失败后应换到另一条链路", cellular.calls() > 0)
        assertTrue(health.get("wifi") != null)
    }
}
