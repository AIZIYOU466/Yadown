package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

/** 位图侧车元数据，对应 docs/ARCHITECTURE.md §2.3。 */
@Entity(
    tableName = "bitmap_sidecar",
    foreignKeys = [
        ForeignKey(
            entity = IntentEntity::class,
            parentColumns = ["id"],
            childColumns = ["intent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class BitmapSidecarEntity(
    @PrimaryKey @ColumnInfo(name = "intent_id") val intentId: String,
    @ColumnInfo(name = "bitmap_path") val bitmapPath: String,
    @ColumnInfo(name = "total_chunks") val totalChunks: Int,
    @ColumnInfo(name = "last_committed_at") val lastCommittedAt: Long
)
