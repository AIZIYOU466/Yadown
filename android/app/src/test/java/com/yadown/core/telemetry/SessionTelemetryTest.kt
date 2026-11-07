package com.yadown.core.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionTelemetryTest {

    @Before
    fun setUp() = SessionTelemetry.reset()

    @After
    fun tearDown() = SessionTelemetry.reset()

    @Test
    fun recordsSessionEventsAndFinish() {
        SessionTelemetry.begin("i1")
        SessionTelemetry.onChunkDone("i1", 1024)
        SessionTelemetry.onChunkDone("i1", 2048)
        SessionTelemetry.onChunkFailed("i1")
        SessionTelemetry.onRetry("i1")
        SessionTelemetry.onStall("i1")
        SessionTelemetry.onSlowSplit("i1")

        val session = SessionTelemetry.current("i1")!!
        assertEquals(2, session.chunksDone)
        assertEquals(1, session.chunksFailed)
        assertEquals(3_072, session.bytesDone)
        assertEquals(1, session.retries)
        assertEquals(1, session.stalls)
        assertEquals(1, session.slowSplits)

        val finished = SessionTelemetry.finish("i1")!!
        assertTrue(finished.finishedAt != null)
        assertNull(SessionTelemetry.current("i1"))
        assertEquals("i1", SessionTelemetry.latest()!!.intentId)
    }

    @Test
    fun recordsTopologyOfSession() {
        SessionTelemetry.begin("t1", "MULTI_CONNECTION")
        assertEquals("MULTI_CONNECTION", SessionTelemetry.current("t1")!!.topology)
    }

    @Test
    fun eventsForUnknownSessionAreIgnored() {
        SessionTelemetry.onChunkDone("missing", 10)
        assertNull(SessionTelemetry.current("missing"))
    }

    @Test
    fun latestPrefersActiveSession() {
        SessionTelemetry.begin("old")
        SessionTelemetry.finish("old")
        SessionTelemetry.begin("new")

        assertEquals("new", SessionTelemetry.latest()!!.intentId)
    }
}
