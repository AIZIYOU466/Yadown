package com.yadown.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yadown.core.db.entity.PeerIndexEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerIndexDao {

    @Query("SELECT * FROM peer_index WHERE last_seen_at >= :since ORDER BY last_seen_at DESC")
    suspend fun getSeenSince(since: Long): List<PeerIndexEntity>

    @Query("SELECT * FROM peer_index ORDER BY last_seen_at DESC")
    fun observeAll(): Flow<List<PeerIndexEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PeerIndexEntity)

    @Query("DELETE FROM peer_index WHERE peer_id = :peerId")
    suspend fun delete(peerId: String)

    @Query("DELETE FROM peer_index WHERE last_seen_at < :before")
    suspend fun deleteSeenBefore(before: Long)
}
