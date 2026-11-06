package com.yadown.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yadown.core.db.entity.ChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chunk: ChunkEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chunks: List<ChunkEntity>)

    @Query("SELECT * FROM chunks WHERE intent_id = :intentId ORDER BY start_byte")
    suspend fun getByIntent(intentId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE intent_id = :intentId ORDER BY start_byte")
    fun observeByIntent(intentId: String): Flow<List<ChunkEntity>>

    @Query(
        "UPDATE chunks SET state = :state, retry_count = :retryCount, " +
            "started_at = :startedAt, completed_at = :completedAt, " +
            "checksum = :checksum, failure_chain_json = :failureChainJson WHERE id = :id"
    )
    suspend fun updateProgress(
        id: String,
        state: String,
        retryCount: Int,
        startedAt: Long?,
        completedAt: Long?,
        checksum: Long?,
        failureChainJson: String?
    )

    @Query("SELECT COUNT(*) FROM chunks WHERE intent_id = :intentId AND state = :state")
    suspend fun countByState(intentId: String, state: String): Int

    @Query("DELETE FROM chunks WHERE intent_id = :intentId")
    suspend fun deleteByIntent(intentId: String)
}
