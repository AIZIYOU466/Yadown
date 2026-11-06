package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 平台能力快照，对应 docs/ARCHITECTURE.md §2.6。 */
@Entity(tableName = "platform_capability")
data class PlatformCapabilityEntity(
    @PrimaryKey val key: String,
    val value: String,
    @ColumnInfo(name = "probed_at") val probedAt: Long,
    @ColumnInfo(name = "ttl_expires_at") val ttlExpiresAt: Long
)
