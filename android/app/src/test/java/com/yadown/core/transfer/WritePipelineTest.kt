package com.yadown.core.transfer

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WritePipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun preallocated(bytes: Int): File {
        val file = tmp.newFile()
        RandomAccessFile(file, "rw").use { it.setLength(bytes.toLong()) }
        return file
    }

    @Test
    fun writesOutOfOrderRangesAtCorrectOffsets() {
        val file = preallocated(16)
        val pipeline = WritePipeline()
        try {
            pipeline.submit(WritePipeline.WriteTask(file.absolutePath, 8, ByteArray(8) { (it + 8).toByte() }))
            pipeline.submit(WritePipeline.WriteTask(file.absolutePath, 0, ByteArray(8) { it.toByte() }))
            pipeline.flush()

            assertArrayEquals(ByteArray(16) { it.toByte() }, file.readBytes())
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun flushWaitsForQueuedWrites() {
        val file = preallocated(4)
        val pipeline = WritePipeline()
        try {
            pipeline.submit(WritePipeline.WriteTask(file.absolutePath, 0, byteArrayOf(1, 2, 3, 4)))
            pipeline.flush()

            assertArrayEquals(byteArrayOf(1, 2, 3, 4), file.readBytes())
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun unwritablePathSurfacesAsFailureOnFlush() {
        val pipeline = WritePipeline()
        try {
            // 目录不能作为文件打开，用于验证写失败会被 flush 暴露。
            val directory = tmp.newFolder("not-a-file")
            pipeline.submit(
                WritePipeline.WriteTask(directory.absolutePath, 0, byteArrayOf(1))
            )
            val failure = runCatching { pipeline.flush() }.exceptionOrNull()

            assertTrue("写盘失败必须在 flush 暴露", failure != null)
        } finally {
            pipeline.close()
        }
    }
}
