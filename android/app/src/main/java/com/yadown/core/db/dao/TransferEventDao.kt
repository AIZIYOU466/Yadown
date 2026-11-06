package com.yadown.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.yadown.core.db.entity.TransferEventEntity

@Dao
interface TransferEventDao {

    @Insert
    suspend fun insert(event: TransferEventEntity)

    @Query("SELECT * FROM transfer_events ORDER BY created_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<TransferEventEntity>

    @Query("SELECT COUNT(*) FROM transfer_events")
    suspend fun count(): Int

    @Query("DELETE FROM transfer_events WHERE created_at < :before")
    suspend fun deleteBefore(before: Long)

    @Query("DELETE FROM transfer_events WHERE intent_id = :intentId")
    suspend fun deleteByIntent(intentId: String)
}
