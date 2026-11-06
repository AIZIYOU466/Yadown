package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 任务级遥测事件：COMPLETED / FAILED / PAUSED。 */
@Entity(
    tableName = "transfer_events",
    indices = [
        Index("intent_id"),
        Index("event"),
        Index("created_at")
    ]
)
data class TransferEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "intent_id") val intentId: String,
    val event: String,
    val cause: String?,
    val bytes: Long,
    @ColumnInfo(name = "elapsed_ms") val elapsedMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long
)
