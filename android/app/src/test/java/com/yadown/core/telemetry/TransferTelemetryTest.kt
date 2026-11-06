package com.yadown.core.telemetry

import com.yadown.core.transfer.FailureCause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TransferTelemetryTest {

    @Before
    fun setUp() = TransferTelemetry.reset()

    @After
    fun tearDown() = TransferTelemetry.reset()

    @Test
    fun aggregatesChunksAndFirstPlayable() {
        TransferTelemetry.onChunkDone(1024)
        TransferTelemetry.onChunkDone(2048)
        TransferTelemetry.onChunkFailed(FailureCause.NETWORK_TIMEOUT)
        TransferTelemetry.onAvailable(1500)
        TransferTelemetry.onAvailable(500)

        val snapshot = TransferTelemetry.snapshot()
        assertEquals(2, snapshot.chunksDone)
        assertEquals(1, snapshot.chunksFailed)
        assertEquals(3072, snapshot.bytesDone)
        assertEquals(2, snapshot.firstPlayableSamples)
        assertEquals(1000, snapshot.firstPlayableAvgMs)
        assertEquals(1L, snapshot.failureByCause[FailureCause.NETWORK_TIMEOUT])
    }

    @Test
    fun resetClearsEverything() {
        TransferTelemetry.onChunkDone(10)
        TransferTelemetry.onChunkFailed(FailureCause.CONTENT_CHANGED)
        TransferTelemetry.reset()

        val snapshot = TransferTelemetry.snapshot()
        assertEquals(0, snapshot.chunksDone)
        assertEquals(0, snapshot.chunksFailed)
        assertEquals(0, snapshot.bytesDone)
        assertEquals(0, snapshot.failureByCause.size)
    }
}
