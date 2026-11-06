package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4FormatResolverTest {

    private fun probe(total: Long) = ProbeResult(total, true, "\"v1\"", null, "video/mp4")

    /** 用内存字节数组充当远端文件，按 Range 语义裁剪。 */
    private fun readerOf(bytes: ByteArray) = RangeReader { start, endInclusive ->
        val from = start.coerceIn(0, bytes.size.toLong()).toInt()
        val to = (endInclusive + 1).coerceIn(0, bytes.size.toLong()).toInt()
        if (from >= to) ByteArray(0) else bytes.copyOfRange(from, to)
    }

    private fun box(type: String, payloadSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val size = payloadSize + 8
        out.write(byteArrayOf(
            (size ushr 24).toByte(), (size ushr 16).toByte(),
            (size ushr 8).toByte(), size.toByte()
        ))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(payloadSize) { 0x20 })
        return out.toByteArray()
    }

    /** ftyp + mdat + moov：moov 在尾部（未做 faststart 的常见布局）。 */
    private fun moovAtTail(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", 24))
        out.write(box("mdat", 4_000))
        out.write(box("moov", 500))
        return out.toByteArray()
    }

    /** ftyp + moov + mdat：faststart 布局，moov 在头部。 */
    private fun moovAtHead(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", 24))
        out.write(box("moov", 500))
        out.write(box("mdat", 4_000))
        return out.toByteArray()
    }

    @Test
    fun locatesMoovAtFileTail() = runTest {
        val bytes = moovAtTail()
        val descriptor = Mp4FormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))

        assertNotNull("应识别出 MP4 并定位 moov", descriptor)
        assertEquals(
            AvailabilityContract.Capability.PLAYABLE,
            descriptor!!.availabilityContract.capability
        )
        // 关键区域：moov 本身必须标记为高价值区间。
        assertTrue(
            "moov 区间权重应为最高档",
            descriptor.valueMap!!.weightAt(moovOffsetTail()) >= 100.0
        )
    }

    @Test
    fun locatesMoovAtFileHead() = runTest {
        val bytes = moovAtHead()
        val descriptor = Mp4FormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))

        assertNotNull(descriptor)
        // moov 紧随 ftyp（32 字节）之后。
        assertEquals(100.0, descriptor!!.valueMap!!.weightAt(32), 0.0)
    }

    @Test
    fun moovChunksScheduledBeforeOtherData() = runTest {
        val bytes = moovAtTail()
        val descriptor = Mp4FormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))!!
        val order = descriptor.valueMap!!.priorityOrder()
        // 最高权重段（moov 起始）必须排第一，保证关键结构优先下发。
        assertEquals(moovOffsetTail(), order.first())
    }

    @Test
    fun returnsNullForNonMp4Content() = runTest {
        val bytes = "this is definitely not an mp4 file, just plain text".toByteArray()
        val descriptor = Mp4FormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))
        assertNull("非 MP4 内容必须返回 null 以走顺序传输", descriptor)
    }

    @Test
    fun returnsNullWhenSizeUnknown() = runTest {
        val bytes = moovAtTail()
        val unknown = ProbeResult(null, true, null, null, "video/mp4")
        assertNull(Mp4FormatResolver().resolve(unknown, readerOf(bytes)))
    }

    @Test
    fun returnsNullWhenMoovMissing() = runTest {
        val out = ByteArrayOutputStream()
        out.write(box("ftyp", 24))
        out.write(box("mdat", 4_000))
        val bytes = out.toByteArray()
        assertNull(Mp4FormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes)))
    }

    private fun moovOffsetTail(): Long = box("ftyp", 24).size.toLong() + box("mdat", 4_000).size
}
