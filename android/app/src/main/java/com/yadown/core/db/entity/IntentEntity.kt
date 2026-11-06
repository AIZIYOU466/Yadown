package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 下载意图，对应 docs/ARCHITECTURE.md §2.1。 */
@Entity(tableName = "intents")
data class IntentEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "content_id") val contentId: String,
    @ColumnInfo(name = "source_url") val sourceUrl: String,
    @ColumnInfo(name = "total_size") val totalSize: Long?,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    val etag: String?,
    @ColumnInfo(name = "last_modified") val lastModified: String?,
    @ColumnInfo(name = "range_supported") val rangeSupported: Boolean,
    @ColumnInfo(name = "value_map_json") val valueMapJson: String?,
    @ColumnInfo(name = "availability_contract_json") val availabilityContractJson: String?,
    /** 与主源内容身份一致的镜像 URL（JSON 数组）；未启用镜像时为 null。 */
    @ColumnInfo(name = "mirror_urls_json") val mirrorUrlsJson: String? = null,
    val state: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
