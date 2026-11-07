package com.yadown.core.io

import java.io.File
import java.net.URI
import java.net.URLDecoder

object DownloadPaths {

    private val FILENAME = Regex("""filename="?([^";]+)"?""")

    fun fileNameOf(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        val fromDisposition = uri?.rawQuery
            ?.split('&')
            ?.mapNotNull { pair ->
                val rawValue = pair.substringAfter('=', missingDelimiterValue = "")
                if (rawValue.isBlank()) null else URLDecoder.decode(rawValue, "UTF-8")
            }
            ?.firstOrNull { it.contains("filename=") }
            ?.let { FILENAME.find(it)?.groupValues?.get(1) }
            ?.takeIf { it.isNotBlank() }
        if (fromDisposition != null) return sanitize(fromDisposition)

        val path = uri?.path.orEmpty()
        return sanitize(path.substringAfterLast('/').ifBlank { "download.bin" })
    }

    fun destFile(filesDir: File, intentId: String, url: String): File =
        File(File(filesDir, "downloads"), "${intentId}_${fileNameOf(url)}")

    private fun sanitize(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifBlank { "download.bin" }
}
