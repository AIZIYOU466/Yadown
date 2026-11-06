package com.yadown.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yadown.core.db.entity.IntentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IntentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(intent: IntentEntity)

    @Query("SELECT * FROM intents WHERE id = :id")
    suspend fun getById(id: String): IntentEntity?

    @Query("SELECT * FROM intents ORDER BY created_at DESC")
    fun observeAll(): Flow<List<IntentEntity>>

    @Query("UPDATE intents SET state = :state, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateState(id: String, state: String, updatedAt: Long)

    @Query("DELETE FROM intents WHERE id = :id")
    suspend fun delete(id: String)
}
