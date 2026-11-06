package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP4 语义解析：定位 `moov`，让播放器在全文下载完成前就能解析轨道并起播。
 *
 * 事实依据（ISO/IEC 14496-12）：文件由 Box 组成，每 Box 为
 * `[4B size][4B type][payload]`；size == 1 时紧随 8 字节 largesize，size == 0 表示延伸到文件末尾。
 * `moov` 承载 `trak`/`stbl`（含 `stco`/`stsz`/`stts`），是起播的必需结构；
 * `mdat` 是媒体数据本体。moov 可以在文件头（faststart）或在文件尾（未做 faststart 的常见情形），
 * 两种布局都必须处理。
 *
 * 降级：结构不合法（非 MP4、fragmented 导致 moov 无法定位、探测读取失败）时返回 null，
 * 调用方按 §T3.5 顺序传输，而不是猜测偏移。
 */
class Mp4FormatResolver(
    private val headProbeSize: Long = DEFAULT_HEAD_PROBE,
    private val tailProbeSize: Long = DEFAULT_TAIL_PROBE
) : FormatResolver {

    override val id: String = ID

    override suspend fun resolve(probe: ProbeResult, reader: RangeReader): ContentDescriptor? {
        val total = probe.totalSize ?: return null
        if (total < MIN_FILE_SIZE) return null

        return runCatching { resolveOrThrow(total, probe.mimeType, reader) }.getOrNull()
    }

    private suspend fun resolveOrThrow(
        total: Long,
        mimeType: String?,
        reader: RangeReader
    ): ContentDescriptor? {
        val headLength = minOf(headProbeSize, total)
        if (!isMp4Header(reader.read(0, headLength - 1))) return null

        val moov = locateMoov(total, reader) ?: return null
        val moovEnd = (moov.offset + moov.size).coerceAtMost(total)
        if (moovEnd <= moov.offset) return null

        // moov 之后紧跟 mdat 头，播放器解析完 moov 会立刻拉第一帧数据，
        // 因此把首个 mdat 的头部区间一并纳入关键区域，避免起播后立刻 stall。
        val mdatHeader = nextBoxHeader(moovEnd, total, reader)
        val readySegments = buildList {
            add(Segment(0, minOf(headLength, total), WEIGHT_HEADER))
            add(Segment(moov.offset, moovEnd, WEIGHT_MOOV))
            mdatHeader?.let { add(Segment(it.offset, it.offset + it.headerSize, WEIGHT_MDAT_HEAD)) }
        }

        return ContentDescriptor(
            contentId = "mp4:$total",
            totalSize = total,
            mimeType = mimeType ?: "video/mp4",
            valueMap = ValueMap.fromSegments(total, readySegments),
            availabilityContract = AvailabilityContract(
                capability = AvailabilityContract.Capability.PLAYABLE,
                readySegments = listOf(Segment(moov.offset, moovEnd, WEIGHT_MOOV))
            ),
            sinkSpec = SinkSpec(fileName = DEFAULT_FILE_NAME)
        )
    }

    private fun isMp4Header(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val buffer = ByteBuffer.wrap(head).order(ByteOrder.BIG_ENDIAN)
        var offset = 0
        while (offset + 8 <= head.size) {
            val size = buffer.getInt(offset).toLong() and 0xFFFFFFFFL
            val type = String(head, offset + 4, 4, Charsets.US_ASCII)
            if (type == BOX_FTYP) return true
            // 首个 Box 就是 mdat/moov 也视为合法 MP4（缺 ftyp 的裸流较罕见但存在）。
            if (type == BOX_MOOV || type == BOX_MDAT) return true
            if (size < 8L || offset + size > head.size) return false
            offset += size.toInt()
        }
        return false
    }

    /** 在头部 Box 链中查找 moov，找不到则回退到尾部扫描。 */
    private suspend fun locateMoov(total: Long, reader: RangeReader): BoxHeader? {
        val headLength = minOf(headProbeSize, total)
        scanBoxChain(0, headLength, reader)?.let { return it }
        // 文件已被头部窗口全量覆盖，不必重复读尾部。
        if (headLength >= total) return null

        // 尾部窗口自适应：小文件少读，大文件封顶，避免探测本身就吃掉可观流量。
        val tailWindow = minOf(tailProbeSize, maxOf(MIN_TAIL_PROBE, total / 8))
        val tailStart = (total - tailWindow).coerceAtLeast(0)
        return scanBoxChain(tailStart, total, reader, allowPartial = true)
    }

    /**
     * 顺序遍历 Box 链查找 [BOX_MOOV]。
     *
     * [allowPartial] 用于尾部窗口：窗口起点可能落在某个 Box 中间，
     * 此时先在窗口内搜索 moov 的类型标记，再按该标记回溯 size 字段。
     * 回溯法能找到 moov，代价是可能误命中 payload 中的同名字节 ——
     * 因此回溯结果必须通过 size 合法性校验，否则放弃（返回 null 走顺序传输）。
     */
    private suspend fun scanBoxChain(
        start: Long,
        endExclusive: Long,
        reader: RangeReader,
        allowPartial: Boolean = false
    ): BoxHeader? {
        if (endExclusive <= start) return null
        val data = reader.read(start, endExclusive - 1)
        if (data.size < 8) return null

        var offset = 0
        while (offset + 8 <= data.size) {
            val size = readUInt32(data, offset)
            val type = String(data, offset + 4, 4, Charsets.US_ASCII)
            val headerSize = if (size == 1L) 16 else 8
            val boxSize = when {
                size == 1L && offset + 16 <= data.size ->
                    readUInt64(data, offset + 8)
                size == 1L -> return null
                size == 0L -> endExclusive - (start + offset) // 延伸到窗口末尾
                else -> size
            }

            if (boxSize < headerSize) {
                // 头部窗口出现非法 size：若允许回溯，改用标记回溯法。
                if (!allowPartial) return null
                return findMoovByMarker(data, start, endExclusive)
            }
            if (type == BOX_MOOV) {
                return BoxHeader(start + offset, boxSize, headerSize)
            }
            if (offset + boxSize > data.size) {
                return if (allowPartial) findMoovByMarker(data, start, endExclusive) else null
            }
            offset += boxSize.toInt()
        }
        return if (allowPartial) findMoovByMarker(data, start, endExclusive) else null
    }

    /** 在窗口内搜索 "moov" 标记，向前回溯 4 字节 size，校验长度合法性后返回。 */
    private fun findMoovByMarker(data: ByteArray, windowStart: Long, total: Long): BoxHeader? {
        val marker = BOX_MOOV.toByteArray(Charsets.US_ASCII)
        var i = 4
        while (i + 4 <= data.size) {
            if (data[i] == marker[0] && data[i + 1] == marker[1] &&
                data[i + 2] == marker[2] && data[i + 3] == marker[3]
            ) {
                val size = readUInt32(data, i - 4)
                val headerSize = if (size == 1L) 16 else 8
                val absoluteOffset = windowStart + i - 4
                val boxSize = when {
                    size == 1L -> -1L
                    size == 0L -> total - absoluteOffset
                    else -> size
                }
                if (boxSize >= headerSize && absoluteOffset + boxSize <= total) {
                    return BoxHeader(absoluteOffset, boxSize, headerSize)
                }
            }
            i++
        }
        return null
    }

    private suspend fun nextBoxHeader(
        offset: Long,
        total: Long,
        reader: RangeReader
    ): BoxHeader? {
        if (offset + 8 > total) return null
        return runCatching {
            val data = reader.read(offset, minOf(offset + 15, total - 1))
            if (data.size < 8) return@runCatching null
            val size = readUInt32(data, 0)
            val headerSize = if (size == 1L) 16 else 8
            if (data.size < headerSize) return@runCatching null
            BoxHeader(offset, size, headerSize)
        }.getOrNull()
    }

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun readUInt64(data: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        }
        return value
    }

    private data class BoxHeader(val offset: Long, val size: Long, val headerSize: Int)

    companion object {
        const val ID = "mp4"
        const val DEFAULT_FILE_NAME = "video.mp4"

        private const val BOX_FTYP = "ftyp"
        private const val BOX_MOOV = "moov"
        private const val BOX_MDAT = "mdat"

        private const val DEFAULT_HEAD_PROBE = 256L * 1024
        private const val DEFAULT_TAIL_PROBE = 2L * 1024 * 1024
        private const val MIN_TAIL_PROBE = 64L * 1024
        private const val MIN_FILE_SIZE = 1_024L

        private const val WEIGHT_HEADER = 30.0
        private const val WEIGHT_MOOV = 100.0
        private const val WEIGHT_MDAT_HEAD = 80.0
    }
}
