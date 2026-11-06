package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityContractTest {

    private fun contract(vararg segments: LongRange) = AvailabilityContract(
        capability = AvailabilityContract.Capability.PLAYABLE,
        readySegments = segments.map { Segment(it.first, it.last + 1, 1.0) }
    )

    @Test
    fun unlocksOnlyWhenEveryReadySegmentDownloaded() {
        val target = contract(100L..199L, 500L..599L)
        assertTrue(target.isAvailable { s, e -> s <= 100 && e >= 199 || s <= 500 && e >= 599 })
    }

    @Test
    fun staysLockedWhenOneSegmentMissing() {
        val target = contract(100L..199L, 500L..599L)
        // 只完成第一段：不能解锁。
        assertTrue(!target.isAvailable { s, _ -> s in 100..199 })
    }

    @Test
    fun emptyContractNeverUnlocks() {
        // 空契约意味着「没有部分可用能力」（例如 APK 必须先下完才能装），不能误判为已解锁。
        assertTrue(!contract().isAvailable { _, _ -> true })
    }

    @Test
    fun segmentRangeArithmeticIsInclusiveOnBothEnds() {
        val segment = Segment(100, 200, 1.0)
        assertEquals(199L, segment.endExclusive - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedSegment() {
        Segment(200, 100, 1.0)
    }
}

class FormatResolverRegistryTest {

    private fun probe(mime: String?) = ProbeResult(1_000, true, null, null, mime)

    private val reader = RangeReader { _, _ -> ByteArray(0) }

    @Test
    fun dispatchesMp4ByMimeType() = runTest {
        val registry = FormatResolverRegistry()
        // 空内容无法解析出 moov，但仍应被分派到 MP4 解析器（返回 null 即走顺序传输）。
        assertNull(registry.resolve(probe("video/mp4"), "https://x/stream", reader))
    }

    @Test
    fun dispatchesApkByExtension() = runTest {
        val registry = FormatResolverRegistry()
        assertNull(registry.resolve(probe(null), "https://x/app.apk", reader))
    }

    @Test
    fun unknownFormatIsNotDispatched() = runTest {
        val registry = FormatResolverRegistry()
        assertNull(registry.resolve(probe("application/zip"), "https://x/archive.zip", reader))
    }

    @Test
    fun extensionMatchingStripsQueryString() = runTest {
        val registry = FormatResolverRegistry()
        // 带查询串的 URL 不应因后缀判断失败而绕过语义解析。
        assertNull(registry.resolve(probe(null), "https://x/video.mp4?token=abc", reader))
    }

    @Test
    fun defaultRegistryCoversThreeFormats() {
        val ids = FormatResolverRegistry.defaultResolvers().map { it.id }.toSet()
        assertEquals(setOf("mp4", "apk", "pdf"), ids)
    }

    @Test
    fun mimeTypeWithCharsetIsNormalised() = runTest {
        val registry = FormatResolverRegistry()
        // Content-Type 常带 charset，必须先去参数再匹配。
        assertNull(registry.resolve(probe("video/mp4; charset=utf-8"), "https://x/stream", reader))
    }

    @Test
    fun invalidDescriptorJsonIsIgnored() {
        assertNotNull(FormatResolverRegistry.defaultResolvers())
    }
}
