package com.yadown.core.transfer

import com.yadown.core.support.FakeChunkDao
import com.yadown.core.support.InMemoryBitmapStore
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommitCoordinatorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun preallocated(file: File, bytes: Int): File {
        RandomAccessFile(file, "rw").use { it.setLength(bytes.toLong()) }
        return file
    }

    @Test
    fun flushThenCommitMarksChunksDone() = runBlocking {
        val file = preallocated(tmp.newFile("f.bin"), 4)
        val chunkDao = FakeChunkDao()
        val bitmap = ChunkBitmap(2)
        val bitmaps = InMemoryBitmapStore()
        val pipeline = WritePipeline()
        val coordinator = CommitCoordinator(
            intentId = "i1",
            chunkDao = chunkDao,
            bitmaps = bitmaps,
            bitmap = bitmap,
            fingerprint = "fp",
            pipeline = pipeline
        )
        try {
            chunkDao.upsert(testChunk("i1-0"))
            pipeline.submit(WritePipeline.WriteTask(file.absolutePath, 0, byteArrayOf(1, 2, 3, 4)))
            bitmap.markDone(0)
            coordinator.enqueue(CommitCoordinator.DoneRecord("i1-0", 0, 10, 42))

            coordinator.commit(force = true)

            assertEquals(ChunkState.DONE.name, chunkDao.chunks.getValue("i1-0").state)
            assertEquals(42L, chunkDao.chunks.getValue("i1-0").checksum)
            assertTrue(bitmaps.load("i1", 2).isDone(0))
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun failedDoneCommitIsRetriedOnNextForce() = runBlocking {
        var failedOnce = false
        val chunkDao = FakeChunkDao(
            failBeforeDone = {
                if (!failedOnce) {
                    failedOnce = true
                    true
                } else {
                    false
                }
            }
        )
        val file = preallocated(tmp.newFile("f.bin"), 4)
        val pipeline = WritePipeline()
        val coordinator = CommitCoordinator(
            intentId = "i1",
            chunkDao = chunkDao,
            bitmaps = InMemoryBitmapStore(),
            bitmap = ChunkBitmap(1),
            fingerprint = null,
            pipeline = pipeline
        )
        try {
            chunkDao.upsert(testChunk("i1-0"))
            pipeline.submit(WritePipeline.WriteTask(file.absolutePath, 0, byteArrayOf(1, 2, 3, 4)))
            coordinator.enqueue(CommitCoordinator.DoneRecord("i1-0", 0, 10, 1))

            assertTrue("首次提交失败但批次应保留", !coordinator.commit(force = false))
            assertEquals(ChunkState.PENDING.name, chunkDao.chunks.getValue("i1-0").state)

            coordinator.commit(force = true)
            assertEquals(ChunkState.DONE.name, chunkDao.chunks.getValue("i1-0").state)
        } finally {
            pipeline.close()
        }
    }

    @Test
    fun writeFailureKeepsChunkPending() = runBlocking {
        val chunkDao = FakeChunkDao()
        val pipeline = WritePipeline()
        val coordinator = CommitCoordinator(
            intentId = "i1",
            chunkDao = chunkDao,
            bitmaps = InMemoryBitmapStore(),
            bitmap = ChunkBitmap(1),
            fingerprint = null,
            pipeline = pipeline
        )
        try {
            chunkDao.upsert(testChunk("i1-0"))
            pipeline.submit(
                WritePipeline.WriteTask(
                    tmp.newFolder("not-a-file").absolutePath,
                    0,
                    byteArrayOf(1)
                )
            )
            coordinator.enqueue(CommitCoordinator.DoneRecord("i1-0", 0, 10, 1))

            val failure = runCatching { coordinator.commit(force = true) }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(ChunkState.PENDING.name, chunkDao.chunks.getValue("i1-0").state)
        } finally {
            pipeline.close()
        }
    }

    private fun testChunk(id: String) = com.yadown.core.db.entity.ChunkEntity(
        id = id,
        intentId = "i1",
        startByte = 0,
        endByte = 3,
        state = ChunkState.PENDING.name,
        retryCount = 0,
        checksum = null,
        connectionFingerprint = null,
        failureChainJson = null,
        startedAt = null,
        completedAt = null
    )
}
