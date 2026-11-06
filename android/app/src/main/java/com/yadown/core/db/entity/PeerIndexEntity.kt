package com.yadown.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** 局域网 peer，对应 docs/ARCHITECTURE.md §2.5。 */
@Entity(tableName = "peer_index")
data class PeerIndexEntity(
    @PrimaryKey @ColumnInfo(name = "peer_id") val peerId: String,
    @ColumnInfo(name = "ip_address") val ipAddress: String,
    val port: Int,
    @ColumnInfo(name = "content_hashes_json") val contentHashesJson: String,
    @ColumnInfo(name = "last_seen_at") val lastSeenAt: Long,
    @ColumnInfo(name = "reliability_score") val reliabilityScore: Double
)
