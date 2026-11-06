package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 分块，对应 docs/ARCHITECTURE.md §2.2。 */
@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = IntentEntity::class,
            parentColumns = ["id"],
            childColumns = ["intent_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("state"), Index("intent_id")]
)
data class ChunkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "intent_id") val intentId: String,
    @ColumnInfo(name = "start_byte") val startByte: Long,
    @ColumnInfo(name = "end_byte") val endByte: Long,
    val state: String,
    @ColumnInfo(name = "retry_count") val retryCount: Int,
    val checksum: Long?,
    @ColumnInfo(name = "connection_fingerprint") val connectionFingerprint: String?,
    @ColumnInfo(name = "failure_chain_json") val failureChainJson: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long?,
    @ColumnInfo(name = "completed_at") val completedAt: Long?
)
