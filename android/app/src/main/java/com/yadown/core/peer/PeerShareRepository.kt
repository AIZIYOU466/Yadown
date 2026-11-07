package com.yadown.core.peer

import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.io.DownloadPaths
import com.yadown.core.transfer.IntentState
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 可共享内容清单：只包含已完成、支持 Range 且文件长度与记录一致的任务。
 * 半成品文件不会出现在清单里，避免对端拼出“每块都校验通过但整体错误”的文件。
 */
class PeerShareRepository(
    private val intentDao: IntentDao,
    private val filesDir: File
) {

    suspend fun contents(): List<SharedContent> =
        intentDao.getByState(IntentState.COMPLETED.name)
            .sortedByDescending { it.updatedAt }
            .mapNotNull { toShared(it) }

    fun observeContents(): Flow<List<SharedContent>> = intentDao.observeAll().map { intents ->
        intents.filter { it.state == IntentState.COMPLETED.name }
            .sortedByDescending { it.updatedAt }
            .mapNotNull { toShared(it) }
    }

    private fun toShared(intent: IntentEntity): SharedContent? {
        if (!intent.rangeSupported) return null
        val size = intent.totalSize ?: return null
        val contentKey = ContentKey.of(intent.sourceUrl) ?: return null
        val file = DownloadPaths.destFile(filesDir, intent.id, intent.sourceUrl)
        if (!file.isFile || file.length() != size) return null
        return SharedContent(
            contentId = intent.id,
            contentKey = contentKey,
            size = size,
            etag = intent.etag,
            lastModified = intent.lastModified,
            fileName = DownloadPaths.fileNameOf(intent.sourceUrl),
            mimeType = intent.mimeType,
            file = file
        )
    }
}
