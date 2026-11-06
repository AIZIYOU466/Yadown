package com.yadown.core.transfer

import java.io.File

interface BitmapStore {
    fun save(intentId: String, bitmap: ChunkBitmap)
    fun load(intentId: String, totalChunks: Int): ChunkBitmap
    fun delete(intentId: String)
}

class FileBitmapStore(private val dir: File) : BitmapStore {
    override fun save(intentId: String, bitmap: ChunkBitmap) {
        dir.mkdirs()
        File(dir, "$intentId.bitset").writeBytes(bitmap.toByteArray())
    }

    override fun load(intentId: String, totalChunks: Int): ChunkBitmap {
        val file = File(dir, "$intentId.bitset")
        return if (file.exists()) {
            ChunkBitmap.fromByteArray(file.readBytes())
        } else {
            ChunkBitmap(totalChunks)
        }
    }

    override fun delete(intentId: String) {
        File(dir, "$intentId.bitset").delete()
    }
}
