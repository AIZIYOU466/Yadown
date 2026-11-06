package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult

/** 按需读取远端字节，供 Resolver 解析头部/尾部结构。 */
fun interface RangeReader {
    suspend fun read(start: Long, endInclusive: Long): ByteArray
}

/**
 * 内容语义解析器（docs/ARCHITECTURE.md §5.1）。
 * 返回 null 表示「该内容不适用此格式」，由调用方回退到顺序传输（§T3.5）。
 */
interface FormatResolver {
    val id: String

    suspend fun resolve(
        probe: ProbeResult,
        reader: RangeReader
    ): ContentDescriptor?
}
