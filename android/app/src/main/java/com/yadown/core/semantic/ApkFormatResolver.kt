package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult

/**
 * APK 语义解析：定位 `AndroidManifest.xml` 与 `resources.arsc` 在包内的字节区间。
 *
 * 事实依据：
 * - APK 是 zip，读取任意条目需要「EOCD → 中央目录 → 本地文件头」三步，不能假设条目顺序；
 * - `AndroidManifest.xml` 是包名/版本/组件声明的唯一来源，系统在安装会话校验阶段必须读取它；
 * - `resources.arsc` 承载资源表，Manifest 中的属性引用要解析到它。
 *
 * 已知边界（已核实，见 P0/T0.3 探针）：普通应用没有 `INSTALL_PACKAGES` 权限，
 * `PackageInstaller.SessionParams.setStaged()` 是 @hide，**无法真正做边下边装**。
 * 因此本解析器只负责让关键字节尽早到达，使「先下完再装」路径能更早开始安装校验，
 * 不声称支持增量安装。
 */
class ApkFormatResolver(
    private val tailProbeSize: Long = DEFAULT_TAIL_PROBE
) : FormatResolver {

    override val id: String = ID

    override suspend fun resolve(probe: ProbeResult, reader: RangeReader): ContentDescriptor? {
        val total = probe.totalSize ?: return null
        if (total < MIN_FILE_SIZE) return null

        return runCatching {
            val eocd = findEocd(total, reader) ?: return@runCatching null
            val cdOffset = eocd.centralDirectoryOffset
            val cdSize = eocd.centralDirectorySize

            val entries = readCentralDirectory(cdOffset, cdSize, reader)
            // 只为两个目标条目回读本地文件头；对每个条目都回读会产生成千上万次请求。
            val manifest = entries.firstOrNull { it.name == ENTRY_MANIFEST }
                ?.let { resolveEntryRange(it, reader) } ?: return@runCatching null
            val arsc = entries.firstOrNull { it.name == ENTRY_ARSC }
                ?.let { resolveEntryRange(it, reader) }

            val segments = buildList {
                add(Segment(0, minOf(HEADER_PROBE, total), WEIGHT_LOCAL_HEADER))
                add(Segment(cdOffset, (cdOffset + cdSize).coerceAtMost(total), WEIGHT_CENTRAL_DIR))
                add(Segment(manifest.dataOffset, manifest.end, WEIGHT_MANIFEST))
                arsc?.let { add(Segment(it.dataOffset, it.end, WEIGHT_ARSC)) }
            }

            ContentDescriptor(
                contentId = "apk:$total",
                totalSize = total,
                mimeType = probe.mimeType ?: MIME_APK,
                valueMap = ValueMap.fromSegments(total, segments),
                availabilityContract = AvailabilityContract(
                    capability = AvailabilityContract.Capability.INSTALLABLE,
                    // 普通应用无 INSTALL_PACKAGES 权限、setStaged() 非公开 API，无法边下边装；
                    // 按 T0.3 降级：整包下载完成才可安装（由 UI 在 COMPLETED 时提供安装入口）。
                    readySegments = emptyList()
                ),
                sinkSpec = SinkSpec(fileName = DEFAULT_FILE_NAME)
            )
        }.getOrNull()
    }

    /** EOCD 固定在文件尾，注释最长 65535 字节，因此只需扫描最后 64KiB。 */
    private suspend fun findEocd(total: Long, reader: RangeReader): Eocd? {
        val window = minOf(total, tailProbeSize)
        val start = total - window
        val data = reader.read(start, total - 1)
        if (data.size < EOCD_MIN_SIZE) return null

        var i = data.size - EOCD_MIN_SIZE
        while (i >= 0) {
            if (readUInt32(data, i) == EOCD_SIGNATURE) {
                val commentLength = readUInt16(data, i + 20)
                if (i + EOCD_MIN_SIZE + commentLength <= data.size) {
                    return Eocd(
                        centralDirectorySize = readUInt32(data, i + 12),
                        centralDirectoryOffset = readUInt32(data, i + 16)
                    )
                }
            }
            i--
        }
        return null
    }

    private suspend fun readCentralDirectory(
        offset: Long,
        size: Long,
        reader: RangeReader
    ): List<Entry> {
        if (size <= 0 || size > MAX_CENTRAL_DIR_SIZE) return emptyList()
        val data = reader.read(offset, offset + size - 1)
        val entries = ArrayList<Entry>()
        var i = 0
        while (i + CENTRAL_HEADER_MIN_SIZE <= data.size) {
            if (readUInt32(data, i) != CENTRAL_SIGNATURE) break
            val nameLength = readUInt16(data, i + 28)
            val extraLength = readUInt16(data, i + 30)
            val commentLength = readUInt16(data, i + 32)
            val compressedSize = readUInt32(data, i + 20)
            val localOffset = readUInt32(data, i + 42)
            val nameStart = i + CENTRAL_HEADER_MIN_SIZE
            if (nameStart + nameLength > data.size) break
            val name = String(data, nameStart, nameLength, Charsets.UTF_8)

            entries += Entry(
                name = name,
                localHeaderOffset = localOffset,
                compressedSize = compressedSize
            )
            i = nameStart + nameLength + extraLength + commentLength
        }
        return entries
    }

    /**
     * 中央目录只给本地文件头偏移；真实数据位置要把本地头长度 + 文件名 + extra 字段加起来。
     * 压缩条目的本地字段长度与中央目录字段长度**可以不同**，所以必须回读本地头。
     */
    private suspend fun resolveEntryRange(entry: Entry, reader: RangeReader): Entry? {
        val header = runCatching {
            reader.read(entry.localHeaderOffset, entry.localHeaderOffset + LOCAL_HEADER_MIN_SIZE - 1)
        }.getOrNull() ?: return null
        if (header.size < LOCAL_HEADER_MIN_SIZE) return null
        if (readUInt32(header, 0) != LOCAL_SIGNATURE) return null

        val nameLength = readUInt16(header, 26)
        val extraLength = readUInt16(header, 28)
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_MIN_SIZE + nameLength + extraLength
        return entry.copy(dataOffset = dataStart)
    }

    // ZIP 全部字段为小端序（PKWARE APPNOTE 4.3.6）。
    private fun readUInt16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    private data class Eocd(val centralDirectoryOffset: Long, val centralDirectorySize: Long)

    private data class Entry(
        val name: String,
        val localHeaderOffset: Long,
        /** 来自中央目录的压缩后长度；条目在文件中的真实字节区间长度。 */
        val compressedSize: Long,
        val dataOffset: Long = 0L
    ) {
        val end: Long get() = dataOffset + compressedSize
    }

    companion object {
        const val ID = "apk"
        const val DEFAULT_FILE_NAME = "app.apk"

        private const val MIME_APK = "application/vnd.android.package-archive"
        private const val ENTRY_MANIFEST = "AndroidManifest.xml"
        private const val ENTRY_ARSC = "resources.arsc"

        private const val EOCD_SIGNATURE = 0x06054b50L
        private const val CENTRAL_SIGNATURE = 0x02014b50L
        private const val LOCAL_SIGNATURE = 0x04034b50L

        private const val EOCD_MIN_SIZE = 22
        private const val CENTRAL_HEADER_MIN_SIZE = 46
        private const val LOCAL_HEADER_MIN_SIZE = 30

        private const val DEFAULT_TAIL_PROBE = 64L * 1024
        private const val MAX_CENTRAL_DIR_SIZE = 8L * 1024 * 1024
        private const val MIN_FILE_SIZE = 1_024L
        private const val HEADER_PROBE = 4L * 1024

        private const val WEIGHT_LOCAL_HEADER = 20.0
        private const val WEIGHT_CENTRAL_DIR = 40.0
        private const val WEIGHT_MANIFEST = 100.0
        private const val WEIGHT_ARSC = 60.0
    }
}
