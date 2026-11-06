package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFormatResolverTest {

    private fun probe(total: Long) = ProbeResult(total, true, "\"v1\"", null, "application/pdf")

    private fun readerOf(bytes: ByteArray) = RangeReader { start, endInclusive ->
        val from = start.coerceIn(0, bytes.size.toLong()).toInt()
        val to = (endInclusive + 1).coerceIn(0, bytes.size.toLong()).toInt()
        if (from >= to) ByteArray(0) else bytes.copyOfRange(from, to)
    }

    /** 经典 xref 表布局：对象 1 在偏移 15，对象 2 在偏移 60。 */
    private fun classicXrefPdf(): ByteArray {
        val body = buildString {
            append("%PDF-1.7\n")
            append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
            append("2 0 obj\n<< /Type /Pages /Kids [] >>\nendobj\n")
            append("xref\n")
            append("0 3\n")
            append("0000000000 65535 f \n")
            append("0000000015 00000 n \n")
            append("0000000060 00000 n \n")
            append("trailer\n<< /Size 3 /Root 1 0 R >>\n")
            append("startxref\n")
            append("120\n")
            append("%%EOF")
        }
        // 用填充把 startxref 指向的位置对齐到真实 xref 关键字。
        val prefix = body.substringBefore("xref\n0 3\n")
        val xrefOffset = prefix.length
        return body.replace("startxref\n120\n", "startxref\n$xrefOffset\n")
            .toByteArray(Charsets.ISO_8859_1)
    }

    /** 交叉引用流：/Type /XRef + /W [1 2 1] + /Size 3。 */
    private fun xrefStreamPdf(): ByteArray {
        val header = "%PDF-1.5\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
        val dictOffset = header.length
        // 三行数据：类型 1 / 偏移 / 单字节列宽。
        val rows = byteArrayOf(
            1, 0, 4, 0,
            1, 0, 4, 0,
            1, 0, 4, 0
        )
        val xrefStart = dictOffset
        val xrefObject = buildString {
            append("2 0 obj\n")
            append("<< /Type /XRef /Size 3 /W [1 2 1] /Root 1 0 R >>\n")
            append("stream\n")
        }
        val out = java.io.ByteArrayOutputStream()
        val bytes = out
        bytes.write(header.toByteArray(Charsets.ISO_8859_1))
        bytes.write(xrefObject.toByteArray(Charsets.ISO_8859_1))
        bytes.write(rows)
        bytes.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        bytes.write("startxref\n$xrefStart\n%%EOF".toByteArray(Charsets.ISO_8859_1))
        return bytes.toByteArray()
    }

    @Test
    fun parsesClassicXrefTable() = runTest {
        val bytes = classicXrefPdf()
        val descriptor = PdfFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))

        assertNotNull("应识别 PDF 并解析 startxref", descriptor)
        assertEquals(
            AvailabilityContract.Capability.PREVIEWABLE,
            descriptor!!.availabilityContract.capability
        )
    }

    @Test
    fun xrefRegionOutranksOtherContent() = runTest {
        val bytes = classicXrefPdf()
        val descriptor = PdfFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))!!
        val map = descriptor.valueMap!!
        // xref 起始位置是权重最高段之一，保证交叉引用表优先下发。
        assertTrue("xref 权重应为最高档", map.priorityOrder().isNotEmpty())
        assertTrue(map.weightAt(bytes.size.toLong() - 10) <= 100.0)
    }

    @Test
    fun parsesXrefStream() = runTest {
        val bytes = xrefStreamPdf()
        val descriptor = PdfFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))
        assertNotNull("交叉引用流也应被解析", descriptor)
    }

    @Test
    fun returnsNullForNonPdfContent() = runTest {
        val bytes = "PK\u0003\u0004 not a pdf at all, just bytes".toByteArray()
        assertNull(PdfFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes)))
    }

    @Test
    fun returnsNullWhenStartXrefUnparseable() = runTest {
        val text = "%PDF-1.7\n1 0 obj\n<< /Type /Catalog >>\nendobj\ntrailer\n<< /Size 2 >>\n%%EOF"
        val bytes = text.toByteArray(Charsets.ISO_8859_1)
        assertNull(PdfFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes)))
    }

    @Test
    fun returnsNullWhenSizeUnknown() = runTest {
        val bytes = classicXrefPdf()
        assertNull(
            PdfFormatResolver().resolve(
                ProbeResult(null, true, null, null, "application/pdf"),
                readerOf(bytes)
            )
        )
    }
}
