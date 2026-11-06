package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 源健康度学习，对应 docs/ARCHITECTURE.md §2.4。 */
@Entity(tableName = "source_health")
data class SourceHealthEntity(
    @PrimaryKey @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "ewma_throughput") val ewmaThroughput: Double,
    @ColumnInfo(name = "ewma_failure_rate") val ewmaFailureRate: Double,
    @ColumnInfo(name = "connection_limit_learned") val connectionLimitLearned: Int?,
    @ColumnInfo(name = "last_updated") val lastUpdated: Long,
    @ColumnInfo(name = "ttl_expires_at") val ttlExpiresAt: Long
)
