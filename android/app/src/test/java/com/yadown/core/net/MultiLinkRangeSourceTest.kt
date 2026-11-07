package com.yadown.core.net

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class MultiLinkRangeSourceTest {

    private class RecordingSource(private val fail: Boolean = false) : RangeSource {
        var calls = 0
            private set
        var probeCalls = 0
            private set

        override suspend fun probe(url: String, linkId: String?): ProbeResult {
            probeCalls++
            return ProbeResult(16, true, "\"v1\"", null, null)
        }

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls++
            if (fail) throw IOException("链路故障")
            val bytes = ByteArray((endInclusive - start + 1).toInt()) { offset ->
                ((start + offset) % 251).toByte()
            }
            return RangePayload(
                bytes = bytes,
                statusCode = 206,
                contentRange = "bytes $start-$endInclusive/16",
                etag = "\"v1\"",
                lastModified = null
            )
        }
    }

    private var now = 1_000L
    private val health = LinkHealthStore(clock = { now })
    private val delegate = RecordingSource()
    private val wifi = RecordingSource()
    private val cellular = RecordingSource()

    private fun source() = MultiLinkRangeSource(
        delegate = delegate,
        linkSources = { id ->
            when (id) {
                "wifi" -> wifi
                "cellular" -> cellular
                else -> null
            }
        },
        health = health,
        clock = { now }
    )

    @Test
    fun linkedRequestUsesLinkSourceAndRecordsHealth() = runBlocking {
        source().readRangeChecked("https://example.com/f", 0, 3, 16, {}, "wifi")

        assertEquals(1, wifi.calls)
        assertEquals(0, delegate.calls)
        assertNotNull(health.get("wifi"))
        assertNull(health.get("cellular"))
    }

    @Test
    fun missingOrUnknownLinkFallsBackToDelegate() = runBlocking {
        source().readRangeChecked("https://example.com/f", 0, 3, 16, {}, null)
        source().readRangeChecked("https://example.com/f", 0, 3, 16, {}, "unknown")

        assertEquals(2, delegate.calls)
        assertEquals(0, wifi.calls)
    }

    @Test
    fun probeUsesLinkSourceWhenPresent() = runBlocking {
        val result = source().probe("https://example.com/f", "cellular")

        assertEquals(16L, result.totalSize)
        assertEquals(1, cellular.probeCalls)
        assertEquals(0, delegate.probeCalls)
    }

    @Test
    fun linkFailureIsRecordedAndPropagated() {
        val failing = MultiLinkRangeSource(
            delegate = delegate,
            linkSources = { RecordingSource(fail = true) },
            health = health,
            clock = { now }
        )

        try {
            runBlocking { failing.readRangeChecked("https://example.com/f", 0, 3, 16, {}, "wifi") }
            fail("链路失败必须向上抛出")
        } catch (_: IOException) {
            val link = health.get("wifi")
            assertNotNull(link)
            assertEquals(1, link!!.consecutiveFailures)
        }
    }
}
