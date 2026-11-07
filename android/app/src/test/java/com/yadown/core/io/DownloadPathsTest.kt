package com.yadown.core.io

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadPathsTest {

    @Test
    fun extractsFilenameFromDispositionQuery() {
        val url = "https://release-assets.githubusercontent.com/github-production-release-asset/" +
            "679086250/9527f7b8-5f38-4dc9-8d5a-c82a69f6a3b9" +
            "?sp=r&rscd=attachment%3B%20filename%3DGhost-Downloader-v4.5.0-Android-arm64-v8a.apk" +
            "&response-content-disposition=attachment%3B%20filename%3DGhost-Downloader-v4.5.0-Android-arm64-v8a.apk"

        assertEquals(
            "Ghost-Downloader-v4.5.0-Android-arm64-v8a.apk",
            DownloadPaths.fileNameOf(url)
        )
    }

    @Test
    fun fallsBackToPathSegment() {
        assertEquals("app.apk", DownloadPaths.fileNameOf("https://cdn.example.com/releases/app.apk"))
    }

    @Test
    fun decodesSpacesAndSanitizesUnsafeChars() {
        val url = "https://cdn.example.com/file?rscd=attachment%3B%20filename%3Da%2Fb%3Ac.apk"

        assertEquals("a_b_c.apk", DownloadPaths.fileNameOf(url))
    }

    @Test
    fun blankUrlFallsBack() {
        assertEquals("download.bin", DownloadPaths.fileNameOf("not a url"))
    }
}
