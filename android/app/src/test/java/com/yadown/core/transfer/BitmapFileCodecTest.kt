package com.yadown.core.transfer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BitmapFileCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun roundTripKeepsBitsAndFingerprint() {
        val bitmap = ChunkBitmap(70).apply {
            markDone(0)
            markDone(63)
            markDone(69)
        }
        val decoded = BitmapFileCodec.decode(
            BitmapFileCodec.encode(bitmap, "plan-1")
        )

        assertNotNull(decoded)
        assertEquals("plan-1", decoded!!.fingerprint)
        assertEquals(70, decoded.bitmap.totalChunks)
        assertTrue(decoded.bitmap.isDone(0))
        assertTrue(decoded.bitmap.isDone(63))
        assertTrue(decoded.bitmap.isDone(69))
        assertEquals(3, decoded.bitmap.doneCount())
    }

    @Test
    fun corruptedPayloadIsRejected() {
        val bytes = BitmapFileCodec.encode(ChunkBitmap(8).apply { markDone(0) }, "plan")
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x7F).toByte()
        assertNull(BitmapFileCodec.decode(bytes))
    }

    @Test
    fun legacyBitmapWithoutHeaderIsRejected() {
        val legacy = ChunkBitmap(8).apply { markDone(0) }.toByteArray()
        assertNull(BitmapFileCodec.decode(legacy))
    }

    @Test
    fun fileStoreRejectsFingerprintMismatchAndCorruption() {
        val dir = tmp.newFolder("bitmaps")
        val store = FileBitmapStore(dir)
        val bitmap = ChunkBitmap(4).apply { markDone(1) }
        store.save("i1", bitmap, "plan-a")

        val loaded = store.loadChecked("i1", 4, "plan-a")
        assertNotNull(loaded)
        assertTrue(loaded!!.isDone(1))

        assertNull(store.loadChecked("i1", 4, "plan-b"))
        assertNull(store.loadChecked("i1", 5, "plan-a"))

        val file = File(dir, "i1.bitset")
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(store.loadChecked("i1", 4, "plan-a"))
        assertTrue(!store.load("i1", 4).isDone(1))
    }
}
