package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkFormatResolverTest {

    private fun probe(total: Long) = ProbeResult(
        total, true, "\"v1\"", null, "application/vnd.android.package-archive"
    )

    private fun readerOf(bytes: ByteArray) = RangeReader { start, endInclusive ->
        val from = start.coerceIn(0, bytes.size.toLong()).toInt()
        val to = (endInclusive + 1).coerceIn(0, bytes.size.toLong()).toInt()
        if (from >= to) ByteArray(0) else bytes.copyOfRange(from, to)
    }

    /** 构造一个最小 zip，条目顺序可控；用高熵字节避免 deflate 把包压到不真实的体积。 */
    private fun zipOf(vararg entries: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, size) ->
                zip.putNextEntry(ZipEntry(name))
                val payload = ByteArray(size)
                java.util.Random(name.hashCode().toLong()).nextBytes(payload)
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun offsetOf(bytes: ByteArray, name: String): Long {
        val needle = name.toByteArray(Charsets.UTF_8)
        outer@ for (i in 0..(bytes.size - needle.size)) {
            for (j in needle.indices) {
                if (bytes[i + j] != needle[j]) continue@outer
            }
            return i.toLong()
        }
        return -1
    }

    @Test
    fun locatesManifestInZip() = runTest {
        val bytes = zipOf(
            "classes.dex" to 2_000,
            "AndroidManifest.xml" to 300,
            "resources.arsc" to 800
        )
        val descriptor = ApkFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))

        assertNotNull("应识别出 APK 并定位 AndroidManifest.xml", descriptor)
        assertEquals(
            AvailabilityContract.Capability.INSTALLABLE,
            descriptor!!.availabilityContract.capability
        )
    }

    @Test
    fun manifestRegionHasHighestWeight() = runTest {
        val bytes = zipOf("classes.dex" to 2_000, "AndroidManifest.xml" to 300)
        val descriptor = ApkFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes))!!
        // Manifest 第一次出现是本地文件头里的名字；数据区起点 = 名字起点 + 名字长度 + extra 长度。
        val nameAt = offsetOf(bytes, "AndroidManifest.xml").toInt()
        val extraLen = ((bytes[nameAt - 2].toInt() and 0xFF) shl 8) or (bytes[nameAt - 1].toInt() and 0xFF)
        val manifestDataOffset = nameAt + "AndroidManifest.xml".length + extraLen
        assertEquals("Manifest 数据区权重应为最高档", 100.0, descriptor.valueMap!!.weightAt(manifestDataOffset.toLong()), 0.0)
    }

    @Test
    fun returnsNullWhenManifestMissing() = runTest {
        val bytes = zipOf("classes.dex" to 2_000, "res/drawable/icon.png" to 500)
        assertNull(ApkFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes)))
    }

    @Test
    fun returnsNullForNonZipContent() = runTest {
        val bytes = ByteArray(4_096) { 0x5A }
        assertNull(ApkFormatResolver().resolve(probe(bytes.size.toLong()), readerOf(bytes)))
    }

    @Test
    fun returnsNullWhenSizeUnknown() = runTest {
        val bytes = zipOf("AndroidManifest.xml" to 300)
        assertNull(
            ApkFormatResolver().resolve(
                ProbeResult(null, true, null, null, null),
                readerOf(bytes)
            )
        )
    }

    @Test
    fun crcGuardIsStableForGeneratedZip() {
        // 生成器自检：确保测试夹具本身是合法 zip，避免把夹具问题误判成解析器 bug。
        val bytes = zipOf("AndroidManifest.xml" to 300)
        val crc = CRC32().apply { update(bytes) }
        assertTrue(crc.value != 0L)
        assertEquals(0x50, bytes[0].toInt() and 0xFF)
        assertEquals(0x4B, bytes[1].toInt() and 0xFF)
    }
}
