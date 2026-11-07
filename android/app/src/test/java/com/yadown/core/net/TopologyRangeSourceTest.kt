package com.yadown.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TopologyRangeSourceTest {

    private class RecordingSource(private val tag: String) : RangeSource {
        val calls = mutableListOf<String>()

        override suspend fun probe(url: String, linkId: String?): ProbeResult {
            calls += "probe"
            return ProbeResult(
                totalSize = 8,
                rangeSupported = true,
                etag = null,
                lastModified = null,
                mimeType = null
            )
        }

        override suspend fun readRange(
            url: String,
            start: Long,
            endInclusive: Long,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls += "readRange"
            return payload()
        }

        override suspend fun readRangeChecked(
            url: String,
            start: Long,
            endInclusive: Long,
            totalSize: Long?,
            onProgress: (Long) -> Unit,
            linkId: String?
        ): RangePayload {
            calls += "readRangeChecked"
            return payload()
        }

        override suspend fun readSequential(
            url: String,
            start: Long,
            onProgress: (Long) -> Unit,
            onBytes: (ByteArray) -> Unit
        ): SequentialResult {
            calls += "readSequential"
            return SequentialResult(bytesWritten = 0, restarted = false)
        }

        override fun toString(): String = tag

        private fun payload() = RangePayload(
            bytes = byteArrayOf(1),
            statusCode = 206,
            contentRange = "bytes 0-0/1",
            etag = null,
            lastModified = null
        )
    }

    private fun callAllEntryPoints(source: RangeSource) = runBlocking {
        source.probe("http://host/file")
        source.readRangeChecked("http://host/file", 0, 1, 8, {}, null)
        source.readRange("http://host/file", 0, 1, {}, null)
        source.readSequential("http://host/file", 0, {}, {})
    }

    @Test
    fun defaultTopologyKeepsSingleClientReuse() {
        val reuse = RecordingSource("reuse")
        var created = 0
        val source = TopologyRangeSource(
            reuse = reuse,
            multiConnection = { created++; RecordingSource("multi") },
            topology = { TransportTopology.REUSE }
        )

        callAllEntryPoints(source)

        assertEquals(
            listOf("probe", "readRangeChecked", "readRange", "readSequential"),
            reuse.calls
        )
        assertEquals("未启用时不应创建多连接客户端", 0, created)
    }

    @Test
    fun multiConnectionTopologyRoutesAllEntryPoints() {
        val reuse = RecordingSource("reuse")
        var multi: RecordingSource? = null
        val source = TopologyRangeSource(
            reuse = reuse,
            multiConnection = { RecordingSource("multi").also { multi = it } },
            topology = { TransportTopology.MULTI_CONNECTION }
        )

        callAllEntryPoints(source)

        assertEquals(emptyList<String>(), reuse.calls)
        assertEquals(
            listOf("probe", "readRangeChecked", "readRange", "readSequential"),
            multi!!.calls
        )
    }

    @Test
    fun multiConnectionSourceIsCreatedOnce() {
        var created = 0
        val source = TopologyRangeSource(
            reuse = RecordingSource("reuse"),
            multiConnection = { created++; RecordingSource("multi") },
            topology = { TransportTopology.MULTI_CONNECTION }
        )

        callAllEntryPoints(source)
        callAllEntryPoints(source)

        assertEquals("多连接客户端应只创建一次", 1, created)
    }

    @Test
    fun switchingTopologyTakesEffectOnNextRequest() {
        var mode = TransportTopology.REUSE
        val reuse = RecordingSource("reuse")
        val multi = RecordingSource("multi")
        val source = TopologyRangeSource(reuse, { multi }, { mode })

        runBlocking { source.readRangeChecked("http://host/file", 0, 1, 8, {}, null) }
        mode = TransportTopology.MULTI_CONNECTION
        runBlocking { source.readRangeChecked("http://host/file", 0, 1, 8, {}, null) }

        assertEquals(listOf("readRangeChecked"), reuse.calls)
        assertEquals(listOf("readRangeChecked"), multi.calls)
    }

    @Test
    fun autoTopologyPicksMultiForLargeFilesAndReuseForSmall() {
        val reuse = RecordingSource("reuse")
        val multi = RecordingSource("multi")
        val source = TopologyRangeSource(
            reuse = reuse,
            multiConnection = { multi },
            topology = { TransportTopology.AUTO },
            resolve = { size ->
                if (size != null && size >= 8L * 1024 * 1024) {
                    TransportTopology.MULTI_CONNECTION
                } else {
                    TransportTopology.REUSE
                }
            }
        )

        runBlocking {
            source.readRangeChecked("http://host/file", 0, 1, 16L * 1024 * 1024, {}, null)
        }
        runBlocking {
            source.readRangeChecked("http://host/file", 0, 1, 1L * 1024 * 1024, {}, null)
        }
        runBlocking { source.probe("http://host/file") }

        assertEquals(listOf("readRangeChecked", "probe"), reuse.calls)
        assertEquals(listOf("readRangeChecked"), multi.calls)
    }
}
