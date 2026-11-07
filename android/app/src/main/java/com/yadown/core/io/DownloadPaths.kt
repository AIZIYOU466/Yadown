package com.yadown.core.io

import java.io.File
import java.net.URI

/** 私有下载目录的命名规则；下载与共享两端必须使用同一规则。 */
object DownloadPaths {

    fun fileNameOf(url: String): String {
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        return path.substringAfterLast('/').ifBlank { "download.bin" }
    }

    fun destFile(filesDir: File, intentId: String, url: String): File =
        File(File(filesDir, "downloads"), "${intentId}_${fileNameOf(url)}")
}
