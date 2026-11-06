package com.yadown.core.player

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadingDataSourceInstrumentedTest {

    @Test
    fun readsDownloadedRangeFromPreallocatedFile() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "yadown-ds-${System.nanoTime()}.bin")
        val data = ByteArray(10) { (it + 1).toByte() }
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(10)
            raf.seek(0)
            raf.write(data)
        }

        val source = DownloadingDataSource(file.absolutePath, "i1") { _, start, end ->
            start >= 0 && end <= 4
        }
        try {
            val length = source.open(
                DataSpec.Builder()
                    .setUri(Uri.fromFile(file))
                    .setPosition(0L)
                    .build()
            )
            assertEquals(10L, length)

            val buffer = ByteArray(5)
            assertEquals(5, source.read(buffer, 0, 5))
            assertArrayEquals(data.copyOfRange(0, 5), buffer)
        } finally {
            source.close()
            file.delete()
        }
    }
}
