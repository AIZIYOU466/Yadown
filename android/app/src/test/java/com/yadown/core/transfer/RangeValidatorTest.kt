package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeValidatorTest {

    private val request = RangeRequest(start = 0, endInclusive = 1023)
    private val session = RangeSession(
        etag = "\"v1\"",
        lastModified = "Wed, 01 Jan 2026 00:00:00 GMT",
        totalSize = 2048
    )

    private fun response(
        statusCode: Int = 206,
        contentRange: String? = "bytes 0-1023/2048",
        etag: String? = "\"v1\"",
        lastModified: String? = "Wed, 01 Jan 2026 00:00:00 GMT",
        bodyLength: Long = 1024
    ) = RangeResponse(statusCode, contentRange, etag, lastModified, bodyLength)

    private fun assertInvalid(cause: FailureCause, result: ValidationResult) {
        assertTrue("expected Invalid, got $result", result is ValidationResult.Invalid)
        assertEquals(cause, (result as ValidationResult.Invalid).cause)
    }

    @Test
    fun valid206() {
        assertEquals(ValidationResult.Valid, RangeValidator.validate(response(), request, session))
    }

    @Test
    fun status200_coveringWholeFile_isValid() {
        val result = RangeValidator.validate(
            response(statusCode = 200, contentRange = null, bodyLength = 2048),
            RangeRequest(0, 2047),
            session
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun status200_partial_isRangeUnsupported() {
        assertInvalid(
            FailureCause.SERVER_RANGE_UNSUPPORTED,
            RangeValidator.validate(response(statusCode = 200, bodyLength = 1024), request, session)
        )
    }

    @Test
    fun status200_unknownTotal_coveringFromZero_isValid() {
        val result = RangeValidator.validate(
            response(statusCode = 200, contentRange = null, bodyLength = 1024),
            RangeRequest(0, 1023),
            session.copy(totalSize = null)
        )
        assertEquals(ValidationResult.Valid, result)
    }

    @Test
    fun unexpectedStatus_isSilentRejection() {
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            RangeValidator.validate(response(statusCode = 404), request, session)
        )
    }

    @Test
    fun missingContentRange_isInvalid() {
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            RangeValidator.validate(response(contentRange = null), request, session)
        )
    }

    @Test
    fun malformedContentRange_isInvalid() {
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            RangeValidator.validate(response(contentRange = "bytes abc-def/2048"), request, session)
        )
    }

    @Test
    fun contentRangeMismatch_isInvalid() {
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            RangeValidator.validate(response(contentRange = "bytes 0-2047/2048"), request, session)
        )
    }

    @Test
    fun contentRangeWildcardTotal_isAccepted() {
        assertEquals(
            ValidationResult.Valid,
            RangeValidator.validate(response(contentRange = "bytes 0-1023/*"), request, session)
        )
    }

    @Test
    fun etagChanged_isContentChanged() {
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            RangeValidator.validate(response(etag = "\"v2\""), request, session)
        )
    }

    @Test
    fun lastModifiedChanged_withoutEtag_isContentChanged() {
        val noEtagSession = session.copy(etag = null)
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            RangeValidator.validate(
                response(etag = null, lastModified = "Thu, 02 Jan 2026 00:00:00 GMT"),
                request,
                noEtagSession
            )
        )
    }

    @Test
    fun bodyLengthMismatch_isChecksumMismatch() {
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            RangeValidator.validate(response(bodyLength = 1000), request, session)
        )
    }

    @Test
    fun bodyLongerThanRequested_isChecksumMismatch() {
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            RangeValidator.validate(response(bodyLength = 2048), request, session)
        )
    }

    @Test
    fun unsatisfiedRangeWithChangedTotal_isContentChanged() {
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            RangeValidator.validate(
                response(statusCode = 416, contentRange = "bytes */4096", bodyLength = 0),
                request,
                session
            )
        )
    }

    @Test
    fun unsatisfiedRangeWithSameTotal_isRangeNotSatisfiable() {
        assertInvalid(
            FailureCause.RANGE_NOT_SATISFIABLE,
            RangeValidator.validate(
                response(statusCode = 416, contentRange = "bytes */2048", bodyLength = 0),
                request,
                session
            )
        )
    }

    @Test
    fun tooManyRequests_isRateLimited() {
        assertInvalid(
            FailureCause.RATE_LIMITED,
            RangeValidator.validate(
                response(statusCode = 429, contentRange = null, bodyLength = 0),
                request,
                session
            )
        )
    }

    @Test
    fun encodedRangeResponse_isRejected() {
        assertInvalid(
            FailureCause.SERVER_SILENT_REJECTION,
            RangeValidator.validate(
                response().copy(contentEncoding = "gzip"),
                request,
                session
            )
        )
    }

    @Test
    fun contentRangeTotalDrift_isContentChanged() {
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            RangeValidator.validate(
                response(contentRange = "bytes 0-1023/4096"),
                request,
                session
            )
        )
    }

    @Test
    fun weakEtagIsNotUsedAsStrongValidator() {
        assertInvalid(
            FailureCause.CONTENT_CHANGED,
            RangeValidator.validate(
                response(etag = "W/\"v1\""),
                request,
                session
            )
        )
    }

    @Test
    fun status200WholeFileBodyLengthMismatch_isChecksumMismatch() {
        assertInvalid(
            FailureCause.CHECKSUM_MISMATCH,
            RangeValidator.validate(
                response(statusCode = 200, contentRange = null, bodyLength = 1000),
                RangeRequest(0, 2047),
                session
            )
        )
    }
}
