package com.yadown.core.semantic

import com.yadown.core.net.ProbeResult

/**
 * 按 MIME / 扩展名把内容分派给对应 [FormatResolver]。
 *
 * 无匹配时返回 null —— 调用方按 §T3.5 退化为顺序传输，不阻塞下载。
 */
class FormatResolverRegistry(
    private val resolvers: List<FormatResolver> = defaultResolvers()
) {
    suspend fun resolve(
        probe: ProbeResult,
        url: String,
        reader: RangeReader
    ): ContentDescriptor? {
        val mime = probe.mimeType?.substringBefore(';')?.trim()?.lowercase()
        val extension = url.substringBefore('?').substringBefore('#')
            .substringAfterLast('.', "").lowercase()

        val resolver = resolvers.firstOrNull { it.id == formatOf(mime, extension) } ?: return null
        return resolver.resolve(probe, reader)
    }

    private fun formatOf(mime: String?, extension: String): String? = when {
        mime in MP4_MIMES || extension in MP4_EXTENSIONS -> Mp4FormatResolver.ID
        mime in APK_MIMES || extension == "apk" -> ApkFormatResolver.ID
        mime in PDF_MIMES || extension == "pdf" -> PdfFormatResolver.ID
        else -> null
    }

    companion object {
        private val MP4_MIMES = setOf("video/mp4", "video/quicktime", "audio/mp4", "application/mp4")
        private val MP4_EXTENSIONS = setOf("mp4", "m4v", "m4a", "mov")
        private val APK_MIMES = setOf(
            "application/vnd.android.package-archive",
            "application/java-archive"
        )
        private val PDF_MIMES = setOf("application/pdf")

        fun defaultResolvers(): List<FormatResolver> = listOf(
            Mp4FormatResolver(),
            ApkFormatResolver(),
            PdfFormatResolver()
        )
    }
}
