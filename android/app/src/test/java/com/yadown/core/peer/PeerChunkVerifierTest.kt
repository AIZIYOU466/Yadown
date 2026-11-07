package com.yadown.core.peer

import com.yadown.core.net.RangePayload
import com.yadown.core.transfer.DownloadException
import com.yadown.core.transfer.FailureCause
import com.yadown.core.transfer.XxHash64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerChunkVerifierTest {

    private val bytes = ByteArray(32) { (it * 5).toByte() }

    private fun payload(hash: Long?, code: Int = 206) = RangePayload(
        bytes = bytes,
        statusCode = code,
        contentRange = if (code == 206) "bytes 0-31/32" else null,
        etag = "\"v1\"",
        lastModified = null,
        peerXxHash64 = hash
    )

    @Test
    fun matchingHashPasses() {
        PeerChunkVerifier.verify(payload(XxHash64.hash(bytes)))
    }

    @Test
    fun missingHashIsRejected() {
        val failure = runCatching { PeerChunkVerifier.verify(payload(null)) }.exceptionOrNull()

        assertTrue(failure is DownloadException)
        assertEquals(FailureCause.CHECKSUM_MISMATCH, (failure as DownloadException).failureCause)
    }

    @Test
    fun wrongHashIsRejected() {
        val failure = runCatching {
            PeerChunkVerifier.verify(payload(XxHash64.hash(bytes) + 1))
        }.exceptionOrNull()

        assertTrue(failure is DownloadException)
        assertEquals(FailureCause.CHECKSUM_MISMATCH, (failure as DownloadException).failureCause)
    }

    @Test
    fun nonSuccessPayloadIsNotVerified() {
        PeerChunkVerifier.verify(payload(null, code = 416))
    }
}
