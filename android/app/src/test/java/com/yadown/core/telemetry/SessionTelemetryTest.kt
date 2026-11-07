package com.yadown.core.telemetry

import com.yadown.core.net.ConnectionMetrics
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionTelemetryTest {

    @Before
    fun setUp() {
        SessionTelemetry.reset()
        ConnectionMetrics.reset()
    }

    @After
    fun tearDown() {
        SessionTelemetry.reset()
        ConnectionMetrics.reset()
    }

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

    @Test
    fun steadyStateRateExcludesStartupAndFinishOverhead() {
        SessionTelemetry.begin("s1")
        SessionTelemetry.onFirstRequest("s1", 2_000)
        SessionTelemetry.onChunkDone("s1", 10_000_000)
        SessionTelemetry.onTransferFinished("s1", 7_000)
        SessionTelemetry.onFlush("s1", 90)

        val session = SessionTelemetry.current("s1")!!
        // 传输窗口 5s，不包括 2s 起步与结束提交
        assertEquals(2_000_000.0, session.steadyStateBytesPerSec, 1.0)
        assertEquals(2_000L, session.firstRequestAtMs)
        assertEquals(7_000L, session.transferFinishedAtMs)
    }

    @Test
    fun steadyStateRateFallsBackToAverageWithoutWindow() {
        SessionTelemetry.begin("s2")
        SessionTelemetry.onChunkDone("s2", 1_000)

        val session = SessionTelemetry.current("s2")!!
        assertEquals(session.avgBytesPerSec, session.steadyStateBytesPerSec, 0.001)
    }

    @Test
    fun reuseRateUsesSessionBaselineOnly() {
        // 会话开始前的建连（例如探测）不能计入本会话复用率
        repeat(3) { ConnectionMetrics.recordConnectStart() }
        repeat(3) { ConnectionMetrics.recordCallStart() }

        SessionTelemetry.begin("s3")
        repeat(10) { ConnectionMetrics.recordCallStart() }
        repeat(4) { ConnectionMetrics.recordConnectStart() }
        val session = SessionTelemetry.finish("s3")!!

        assertEquals(10, session.callStarts)
        assertEquals(4, session.connectStarts)
        assertEquals(0.6, session.reuseRate, 0.001)
    }
}
