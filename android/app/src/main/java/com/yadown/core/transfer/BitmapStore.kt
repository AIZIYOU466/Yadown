package com.yadown.core.transfer

import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface BitmapStore {
    fun save(intentId: String, bitmap: ChunkBitmap)
    fun load(intentId: String, totalChunks: Int): ChunkBitmap
    fun delete(intentId: String)

    /** 带计划指纹的保存；不实现指纹的存储可退回两参版本。 */
    fun save(intentId: String, bitmap: ChunkBitmap, fingerprint: String?) {
        save(intentId, bitmap)
    }

    /**
     * 校验式加载：文件损坏、指纹不符或 totalChunks 不一致时返回 null。
     * 调用方应把它当作“位图不可用”，从 DB 重建，而不是信任空位图。
     */
    fun loadChecked(intentId: String, totalChunks: Int, fingerprint: String? = null): ChunkBitmap? =
        runCatching { load(intentId, totalChunks) }.getOrNull()
}

class FileBitmapStore(private val dir: File) : BitmapStore {

    override fun save(intentId: String, bitmap: ChunkBitmap) {
        save(intentId, bitmap, null)
    }

    override fun save(intentId: String, bitmap: ChunkBitmap, fingerprint: String?) {
        dir.mkdirs()
        val target = fileFor(intentId)
        val tmp = File(dir, "$intentId.bitset.tmp")
        val bytes = BitmapFileCodec.encode(bitmap, fingerprint.orEmpty())

        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }

        runCatching {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        }.getOrElse {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
        fsyncDirectory()
    }

    override fun load(intentId: String, totalChunks: Int): ChunkBitmap =
        loadChecked(intentId, totalChunks) ?: ChunkBitmap(totalChunks)

    override fun loadChecked(
        intentId: String,
        totalChunks: Int,
        fingerprint: String?
    ): ChunkBitmap? {
        val file = fileFor(intentId)
        if (!file.exists()) return null

        val decoded = runCatching { BitmapFileCodec.decode(file.readBytes()) }.getOrNull()
            ?: return null
        if (decoded.bitmap.totalChunks != totalChunks) return null
        if (fingerprint != null && decoded.fingerprint != fingerprint) return null
        return decoded.bitmap
    }

    override fun delete(intentId: String) {
        fileFor(intentId).delete()
        File(dir, "$intentId.bitset.tmp").delete()
    }

    private fun fileFor(intentId: String): File = File(dir, "$intentId.bitset")

    private fun fsyncDirectory() {
        runCatching {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
