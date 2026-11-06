package com.yadown.core.telemetry

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelemetryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun appendsAndReadsLines() {
        val file = File(tmp.root, "telemetry/events.ndjson")
        val store = TelemetryStore(file)

        store.append("chunk_done,1024")
        store.append("chunk_failed,NETWORK_TIMEOUT")

        assertEquals(
            listOf("chunk_done,1024", "chunk_failed,NETWORK_TIMEOUT"),
            store.readLines()
        )
    }

    @Test
    fun rotatesWhenExceedingLimit() {
        val file = File(tmp.root, "events.ndjson")
        val store = TelemetryStore(file, maxBytes = 1)

        store.append("first")
        store.append("second")

        assertTrue(File(tmp.root, "events.ndjson.1").exists())
        assertEquals(listOf("second"), store.readLines())
    }
}
