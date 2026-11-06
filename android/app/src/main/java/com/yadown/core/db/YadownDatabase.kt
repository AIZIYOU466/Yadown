package com.yadown.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.entity.BitmapSidecarEntity
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.db.entity.PeerIndexEntity
import com.yadown.core.db.entity.PlatformCapabilityEntity
import com.yadown.core.db.entity.SourceHealthEntity

@Database(
    entities = [
        IntentEntity::class,
        ChunkEntity::class,
        BitmapSidecarEntity::class,
        SourceHealthEntity::class,
        PeerIndexEntity::class,
        PlatformCapabilityEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class YadownDatabase : RoomDatabase() {
    abstract fun intentDao(): IntentDao
    abstract fun chunkDao(): ChunkDao
}
