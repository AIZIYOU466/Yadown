package com.yadown.core.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yadown.core.db.entity.SourceHealthEntity

@Dao
interface SourceHealthDao {

    @Query("SELECT * FROM source_health WHERE source_key = :sourceKey")
    suspend fun get(sourceKey: String): SourceHealthEntity?

    @Query("SELECT * FROM source_health WHERE ttl_expires_at > :now")
    suspend fun getValid(now: Long): List<SourceHealthEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SourceHealthEntity)

    @Query("DELETE FROM source_health WHERE ttl_expires_at <= :now")
    suspend fun deleteExpired(now: Long)
}
