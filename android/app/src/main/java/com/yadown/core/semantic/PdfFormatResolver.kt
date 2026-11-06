package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import java.util.regex.Pattern

/**
 * PDF 语义解析：定位 xref 交叉引用表与首页对象，让 PDF 在全文下载完成前就能打开并显示首页。
 *
 * 事实依据（PDF 32000-1:2008）：
 * - 文件尾 `trailer` 中的 `startxref` 给出 xref 表/流的字节偏移；
 * - xref 表把对象号映射到字节偏移，`Root`（目录对象）由 trailer 的 `/Root n 0 R` 指出；
 * - 首页内容由 `/Pages` 树下的第一个 `/Page` 对象决定。
 *
 * 两种 xref 形态都必须支持：传统 `xref` 表（交叉引用表）与 PDF 1.5+ 的交叉引用流
 * （`/Type /XRef`），后者在增量更新与对象流场景下更常见。
 *
 * 降级：线性化损坏、加密、或 startxref 指向的偏移不是 xref 时返回 null，调用方顺序传输。
 */
class PdfFormatResolver(
    private val tailProbeSize: Long = DEFAULT_TAIL_PROBE
) : FormatResolver {

    override val id: String = ID

    override suspend fun resolve(probe: ProbeResult, reader: RangeReader): ContentDescriptor? {
        val total = probe.totalSize ?: return null
        if (total < MIN_FILE_SIZE) return null

        return runCatching {
            val tail = reader.read((total - minOf(total, tailProbeSize)), total - 1)
            if (!looksLikePdf(reader)) return@runCatching null

            val startXref = findStartXref(tail) ?: return@runCatching null
            if (startXref < 0 || startXref >= total) return@runCatching null

            val xrefBytes = reader.read(startXref, minOf(startXref + XREF_PROBE, total) - 1)
            val entries = parseXref(xrefBytes, startXref)
            // Root 对象由 trailer 的 /Root n 0 R 指定；取不到时退到最小偏移（通常是首个对象）。
            val rootNumber = findRootNumber(String(tail, Charsets.ISO_8859_1))
            val rootOffset = rootNumber?.let { entries[it] } ?: entries.values.minOrNull()

            val segments = buildList {
                add(Segment(0, minOf(HEADER_PROBE, total), WEIGHT_HEADER))
                add(Segment(startXref, minOf(startXref + XREF_PROBE, total), WEIGHT_XREF))
                rootOffset?.let { offset ->
                    add(Segment(offset, minOf(offset + OBJECT_PROBE, total), WEIGHT_ROOT))
                }
            }

            ContentDescriptor(
                contentId = "pdf:$total",
                totalSize = total,
                mimeType = probe.mimeType ?: MIME_PDF,
                valueMap = ValueMap.fromSegments(total, segments),
                availabilityContract = AvailabilityContract(
                    capability = AvailabilityContract.Capability.PREVIEWABLE,
                    // 应用内暂无 PDF 渲染器：只做「目录优先」下发，不承诺部分可预览。
                    // 接入查看器后可把 xref+首页对象区间放进 readySegments。
                    readySegments = emptyList()
                ),
                sinkSpec = SinkSpec(fileName = DEFAULT_FILE_NAME)
            )
        }.getOrNull()
    }

    private suspend fun looksLikePdf(reader: RangeReader): Boolean {
        val head = runCatching { reader.read(0, HEADER_PROBE - 1) }.getOrNull() ?: return false
        if (head.size < 5) return false
        // 规范允许文件头前有少量偏移（某些生成器会写 BOM 或前导字节）。
        val limit = minOf(head.size - 4, PDF_HEADER_SEARCH)
        for (i in 0 until limit) {
            if (head[i] == '%'.code.toByte() &&
                head[i + 1] == 'P'.code.toByte() &&
                head[i + 2] == 'D'.code.toByte() &&
                head[i + 3] == 'F'.code.toByte()
            ) {
                return true
            }
        }
        return false
    }

    private fun findRootNumber(text: String): Int? {
        val matcher = ROOT_REF.matcher(text)
        var last: Int? = null
        while (matcher.find()) {
            last = matcher.group(1).toIntOrNull()
        }
        return last
    }

    /** 取最后一个 startxref（增量更新的 PDF 会附加多份，最新的在最后）。 */
    private fun findStartXref(tail: ByteArray): Long? {
        val text = String(tail, Charsets.ISO_8859_1)
        val matcher = START_XREF.matcher(text)
        var last: Long? = null
        while (matcher.find()) {
            val value = text.substring(matcher.end()).trimStart()
                .takeWhile { it.isDigit() }.toLongOrNull()
            if (value != null) last = value
        }
        return last
    }

    /**
     * 解析 xref：既支持 `xref` 关键字后的经典表，也支持交叉引用流的 `/W` 宽度数组。
     * 返回「对象号 → 字节偏移」，用于在一堆对象里挑出 Root。
     */
    private fun parseXref(data: ByteArray, startXref: Long): Map<Int, Long> {
        val text = String(data, Charsets.ISO_8859_1)
        return if (text.trimStart().startsWith("xref")) {
            parseXrefTable(text)
        } else {
            parseXrefStream(data, text)
        }
    }

    private fun parseXrefTable(text: String): Map<Int, Long> {
        val result = LinkedHashMap<Int, Long>()
        val body = text.substringAfter("xref")
        val subsections = SUBSECTION.matcher(body)
        while (subsections.find()) {
            val first = subsections.group(1).toIntOrNull() ?: continue
            val count = subsections.group(2).toIntOrNull() ?: continue
            val rest = body.substring(subsections.end())
            var consumed = 0
            for (i in 0 until count) {
                val entry = ENTRY_LINE.matcher(rest.substring(consumed.coerceAtMost(rest.length)))
                if (!entry.find() || entry.start() > 2) break
                // 自由条目（f）没有对象，不能当作可预览目标。
                if (entry.group(3) != "n") {
                    consumed += entry.end()
                    continue
                }
                val offset = entry.group(2).toLongOrNull() ?: break
                result[first + i] = offset
                consumed += entry.end()
            }
        }
        return result
    }

    /**
     * 交叉引用流：`/W [w1 w2 w3]` 描述每列字节宽度，`/Size` 是对象总数。
     * 第 0 列是字段类型（0=free、1=offset、2=in-obj-stream），这里只收类型 1 的偏移。
     */
    private fun parseXrefStream(data: ByteArray, text: String): Map<Int, Long> {
        val matcher = W_ARRAY.matcher(text)
        if (!matcher.find()) return emptyMap()
        val widths = matcher.group(1).trim().split(Regex("\\s+"))
            .mapNotNull { v -> v.toIntOrNull() }
        if (widths.size < 3) return emptyMap()
        val rowWidth = widths.sum()
        if (rowWidth <= 0) return emptyMap()

        val sizeMatcher = SIZE.matcher(text)
        val size = if (sizeMatcher.find()) sizeMatcher.group(1).toIntOrNull() else null
        if (size == null) return emptyMap()

        val streamStart = data.indexOfSubArray("stream".toByteArray(Charsets.US_ASCII))
        if (streamStart < 0) return emptyMap()
        var payloadStart = streamStart + "stream".length
        while (payloadStart < data.size && (data[payloadStart] == '\r'.code.toByte() ||
                data[payloadStart] == '\n'.code.toByte())
        ) {
            payloadStart++
        }

        val result = LinkedHashMap<Int, Long>()
        for (row in 0 until size) {
            val base = payloadStart + row * rowWidth
            if (base + rowWidth > data.size) break
            var cursor = base
            var type = 1L
            if (widths[0] > 0) {
                type = readBE(data, cursor, widths[0])
                cursor += widths[0]
            }
            val field = readBE(data, cursor, widths[1])
            if (type == 1L && field > 0) result[row] = field
        }
        return result
    }

    private fun readBE(data: ByteArray, offset: Int, width: Int): Long {
        var value = 0L
        for (i in 0 until width) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private fun ByteArray.indexOfSubArray(target: ByteArray): Int {
        if (target.isEmpty() || target.size > size) return -1
        outer@ for (i in 0..(size - target.size)) {
            for (j in target.indices) {
                if (this[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        const val ID = "pdf"
        const val DEFAULT_FILE_NAME = "document.pdf"

        private const val MIME_PDF = "application/pdf"

        private const val DEFAULT_TAIL_PROBE = 64L * 1024
        private const val HEADER_PROBE = 1L * 1024
        private const val XREF_PROBE = 4L * 1024
        private const val OBJECT_PROBE = 4L * 1024
        private const val MIN_FILE_SIZE = 64L
        private const val PDF_HEADER_SEARCH = 1_024

        private const val WEIGHT_HEADER = 20.0
        private const val WEIGHT_XREF = 100.0
        private const val WEIGHT_ROOT = 90.0

        private val START_XREF = Pattern.compile("startxref")
        private val ROOT_REF = Pattern.compile("/Root\\s+(\\d+)\\s+\\d+\\s+R")
        private val SUBSECTION = Pattern.compile("(\\d+)\\s+(\\d+)\\s*\\r?\\n")
        private val ENTRY_LINE = Pattern.compile("(\\d{10})\\s(\\d{5})\\s([nf])[ \\t]*[\\r\\n]+")
        private val W_ARRAY = Pattern.compile("/W\\s*\\[([^\\]]*)\\]")
        private val SIZE = Pattern.compile("/Size\\s+(\\d+)")
    }
}
