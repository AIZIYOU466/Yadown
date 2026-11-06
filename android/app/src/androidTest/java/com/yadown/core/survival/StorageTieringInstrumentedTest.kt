package com.yadown.core.survival

import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StorageTieringInstrumentedTest {

    @Test
    fun publishesCompletedFileToPublicDownloads() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        assumeTrue(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED)

        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "yadown-test-${System.nanoTime()}.bin")
        val bytes = ByteArray(32) { (it % 251).toByte() }
        file.writeBytes(bytes)

        val result = StorageTiering(context).publishToPublic(
            file = file,
            displayName = file.name,
            expectedSize = bytes.size.toLong(),
            mimeType = "application/octet-stream"
        )

        try {
            assertTrue("publish failed: ${result.reason}", result.moved)
            val uri = result.publicUri
            assertNotNull(uri)
            context.contentResolver.openInputStream(uri!!).use { input ->
                assertNotNull(input)
                assertArrayEquals(bytes, input!!.readBytes())
            }
        } finally {
            result.publicUri?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            file.delete()
        }
    }
}
