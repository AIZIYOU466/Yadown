package com.yadown.core.bench

import com.yadown.core.io.RandomAccessFileStore
import com.yadown.core.net.OkHttpRangeSource
import com.yadown.core.semantic.Segment
import com.yadown.core.semantic.ValueMap
import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.FakeIntentDao
import com.yadown.core.support.HttpResponse
import com.yadown.core.support.InMemoryBitmapStore
import com.yadown.core.support.TestHttpServer
import com.yadown.core.support.seedTestChunks
import com.yadown.core.support.testIntent
import com.yadown.core.transfer.ChunkRange
import com.yadown.core.transfer.DownloadEngine
import com.yadown.core.transfer.IntentState
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 能力解锁基准：不追求峰值 MB/s，而是度量「关键区间何时可用」。
 *
 * 场景：2MB 文件、256KB 分块；前 512KB 视为首播关键区间（权重 90，其余 1）。
 * 网络：每连接限速 + 固定 RTT，复现单连接受限的真实 CDN。
 * 指标：TTFP（关键区间全部完成的时间）、总耗时、请求数、峰值并发。
 * 结果写入 `build/reports/yadown-benchmark.json`，供 CI/本地对比基线。
 */
class CapabilityBenchmarkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val totalBytes = 2L * 1024 * 1024
    private val chunkSize = 256L * 1024
    private val keyRangeEnd = 512L * 1024

    @Test
    fun benchmarkReportsCapabilityMetrics() = runBlocking {
        val results = mutableListOf<ScenarioResult>()

        results += runScenario(name = "slow-1MBps", bytesPerSec = 1_000_000, latencyMs = 30)
        results += runScenario(name = "fast-4MBps", bytesPerSec = 4_000_000, latencyMs = 30)

        val reportDir = File("build/reports")
        reportDir.mkdirs()
        File(reportDir, "yadown-benchmark.json").writeText(toJson(results))

        results.forEach { result ->
            assertTrue("${result.name} 请求数应为 8，实际 ${result.requests}", result.requests == 8)
            assertTrue("${result.name} 应完成", result.completed)
            assertTrue("${result.name} TTFP ${result.ttfpMs}ms 不应晚于总耗时 ${result.totalMs}ms", result.ttfpMs <= result.totalMs)
        }
    }

    private suspend fun runScenario(name: String, bytesPerSec: Int, latencyMs: Long): ScenarioResult {
        val payload = ByteArray(totalBytes.toInt()) { (it * 31 % 251).toByte() }
        val etag = "\"$name\""
        val server = TestHttpServer(
            throttleBytesPerSec = bytesPerSec,
            latencyMs = latencyMs
        ) { request ->
            val range = parseRange(request.headers["range"])
            if (range == null) {
                HttpResponse(200, mapOf("ETag" to etag), payload)
            } else {
                val end = minOf(range.last, totalBytes - 1)
                HttpResponse(
                    206,
                    mapOf(
                        "Content-Range" to "bytes ${range.first}-$end/$totalBytes",
                        "ETag" to etag
                    ),
                    payload.copyOfRange(range.first.toInt(), end.toInt() + 1)
                )
            }
        }

        try {
            val intentDao = FakeIntentDao()
            val chunkDao = FakeChunkDao()
            intentDao.upsert(testIntent(totalBytes, etag, id = "bench"))
            seedTestChunks(chunkDao, totalBytes, chunkSize, intentId = "bench")
            val file = tmp.newFile()

            val startedAt = System.nanoTime()
            val keyReadyAt = AtomicLong(0)
            val coveredBytes = AtomicLong(0)

            val engine = DownloadEngine(
                source = OkHttpRangeSource(
                    OkHttpClient.Builder()
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                ),
                files = RandomAccessFileStore(),
                bitmaps = InMemoryBitmapStore(),
                intentDao = intentDao,
                chunkDao = chunkDao
            )

            val valueMap = ValueMap.fromSegments(
                totalBytes,
                listOf(
                    Segment(0, keyRangeEnd, 90.0),
                    Segment(keyRangeEnd, totalBytes, 1.0)
                )
            )

            engine.download(
                intentId = "bench",
                url = server.baseUrl,
                destPath = file.absolutePath,
                concurrency = 4,
                chunkSize = chunkSize,
                valueMap = valueMap,
                onChunkDone = { chunk: ChunkRange ->
                    val covered = coveredBytes.addAndGet(chunk.endInclusive - chunk.start + 1)
                    if (keyReadyAt.get() == 0L && coversKeyRange(chunkDao, chunk)) {
                        keyReadyAt.set((System.nanoTime() - startedAt) / 1_000_000)
                    }
                }
            )

            val totalMs = (System.nanoTime() - startedAt) / 1_000_000
            val ttfpMs = if (keyReadyAt.get() > 0) keyReadyAt.get() else totalMs
            assertEquals(IntentState.COMPLETED.name, intentDao.getById("bench")!!.state)
            assertEquals(totalBytes, file.length())

            return ScenarioResult(
                name = name,
                bytesPerSec = bytesPerSec,
                latencyMs = latencyMs,
                ttfpMs = ttfpMs,
                totalMs = totalMs,
                requests = server.requests.size,
                connectionCount = server.connectionCount,
                completed = true
            )
        } finally {
            server.close()
        }
    }

    private fun coversKeyRange(chunkDao: FakeChunkDao, latest: ChunkRange): Boolean {
        val doneRanges = chunkDao.chunks.values
            .filter { it.state == com.yadown.core.transfer.ChunkState.DONE.name }
            .map { it.startByte..it.endByte }
            .sortedBy { it.first }
        var cursor = 0L
        for (range in doneRanges) {
            if (range.first > cursor) break
            cursor = maxOf(cursor, range.last + 1)
            if (cursor >= keyRangeEnd) return true
        }
        return cursor >= keyRangeEnd
    }

    private fun parseRange(header: String?): LongRange? {
        val match = header?.let { Regex("bytes=(\\d+)-(\\d+)").find(it) } ?: return null
        return match.groupValues[1].toLong()..match.groupValues[2].toLong()
    }

    private fun toJson(results: List<ScenarioResult>): String = buildString {
        append("{\n  \"scenarios\": [\n")
        results.forEachIndexed { index, r ->
            append("    {\"name\": \"${r.name}\", \"bytesPerSec\": ${r.bytesPerSec}, ")
            append("\"latencyMs\": ${r.latencyMs}, \"ttfpMs\": ${r.ttfpMs}, ")
            append("\"totalMs\": ${r.totalMs}, \"requests\": ${r.requests}, ")
            append("\"connections\": ${r.connectionCount}, \"completed\": ${r.completed}}")
            if (index != results.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n}\n")
    }

    private data class ScenarioResult(
        val name: String,
        val bytesPerSec: Int,
        val latencyMs: Long,
        val ttfpMs: Long,
        val totalMs: Long,
        val requests: Int,
        val connectionCount: Int,
        val completed: Boolean
    )
}
