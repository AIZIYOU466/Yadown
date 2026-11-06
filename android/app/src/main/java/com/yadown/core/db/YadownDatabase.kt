package com.yadown.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yadown.core.db.dao.ChunkDao
import com.yadown.core.db.dao.IntentDao
import com.yadown.core.db.dao.PeerIndexDao
import com.yadown.core.db.dao.SourceHealthDao
import com.yadown.core.db.dao.TransferEventDao
import com.yadown.core.db.entity.BitmapSidecarEntity
import com.yadown.core.db.entity.ChunkEntity
import com.yadown.core.db.entity.IntentEntity
import com.yadown.core.db.entity.PeerIndexEntity
import com.yadown.core.db.entity.PlatformCapabilityEntity
import com.yadown.core.db.entity.SourceHealthEntity
import com.yadown.core.db.entity.TransferEventEntity

@Database(
    entities = [
        IntentEntity::class,
        ChunkEntity::class,
        BitmapSidecarEntity::class,
        SourceHealthEntity::class,
        PeerIndexEntity::class,
        PlatformCapabilityEntity::class,
        TransferEventEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class YadownDatabase : RoomDatabase() {
    abstract fun intentDao(): IntentDao
    abstract fun chunkDao(): ChunkDao
    abstract fun transferEventDao(): TransferEventDao
    abstract fun sourceHealthDao(): SourceHealthDao
    abstract fun peerIndexDao(): PeerIndexDao

    companion object {
        /** v2 只增加任务级遥测表，不改动既有表。 */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfer_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `intent_id` TEXT NOT NULL,
                        `event` TEXT NOT NULL,
                        `cause` TEXT,
                        `bytes` INTEGER NOT NULL,
                        `elapsed_ms` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_events_intent_id` " +
                        "ON `transfer_events` (`intent_id`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_events_event` " +
                        "ON `transfer_events` (`event`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transfer_events_created_at` " +
                        "ON `transfer_events` (`created_at`)"
                )
            }
        }

        /** v3 为 intents 增加镜像 URL 列表、为 source_health 增加延迟 EWMA；peer_index 在 v1 已建表。 */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `intents` ADD COLUMN `mirror_urls_json` TEXT")
                db.execSQL(
                    "ALTER TABLE `source_health` ADD COLUMN `ewma_latency` REAL NOT NULL DEFAULT 0.0"
                )
            }
        }
    }
}
