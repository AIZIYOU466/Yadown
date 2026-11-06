package com.yadown.core.survival

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * P2/T2.4 存储分层落盘。
 *
 * 依据 docs/ARCHITECTURE.md：下载过程中的随机写落在私有目录（`files/downloads`），
 * 完成后顺序迁移到公共媒体库。原因有两条：
 * 1. 公共目录（Scoped Storage）在部分 ROM 上按顺序写访问更慢，且中途写入会被媒体扫描；
 *    而随机写（断点续传必然产生）在公共目录会触发额外同步开销。
 * 2. 用户可见的成品文件应出现在标准位置，而不是应用私有目录。
 *
 * 迁移是「完成后一次性顺序拷贝 + 长度校验」。注意：这里**拷贝而非移动**，
 * 因为播放器与「打开」都依赖私有文件路径（边下边播的区间判定也基于它），
 * 删掉源文件会让已完成任务无法在应用内打开。代价是完成后磁盘占用翻倍，
 * 任务删除时私有副本会一并清理。
 */
class StorageTiering(private val context: Context) {

    data class Result(
        val moved: Boolean,
        val publicUri: Uri?,
        val reason: String?
    )

    /**
     * 把已完成的私有文件迁移到公共 Downloads 集合。
     *
     * [expectedSize] 用于校验迁移完整性：长度不符则放弃迁移，保留私有文件。
     * 返回 [Result.moved] == false 时 [Result.reason] 说明原因，调用方据此决定是否重试。
     */
    fun publishToPublic(file: File, displayName: String, expectedSize: Long, mimeType: String): Result {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 10 以下没有 MediaStore.Downloads，写公共目录需要存储权限；
            // 本版不做该路径，保留私有副本由 FileProvider 提供。
            return Result(false, null, "Android 10 以下不做公共目录迁移")
        }
        if (!file.exists()) return Result(false, null, "源文件不存在")
        if (file.length() != expectedSize) {
            return Result(false, null, "源文件长度 ${file.length()} 与预期 $expectedSize 不符")
        }

        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
                put(MediaStore.Downloads.RELATIVE_PATH, defaultRelativePath())
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection(), values)
                ?: return Result(false, null, "无法创建公共条目")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
                } ?: return Result(false, null, "无法打开公共条目输出流")

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                Result(true, uri, null)
            } catch (e: Exception) {
                // 迁移失败：清掉半成品公共条目，保留私有文件，避免出现两个不完整副本。
                runCatching { resolver.delete(uri, null, null) }
                Result(false, null, e.message ?: "写入公共目录失败")
            }
        }.getOrElse { Result(false, null, it.message ?: "迁移失败") }
    }

    private fun collection(): Uri = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun defaultRelativePath(): String =
        Environment.DIRECTORY_DOWNLOADS + "/Yadown"

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
