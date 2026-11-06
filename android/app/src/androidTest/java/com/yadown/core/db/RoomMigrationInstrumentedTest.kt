package com.yadown.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 直接对 v1 数据库执行 MIGRATION_1_2，验证新增 telemetry 表与索引的 SQL 可执行。
 * Room 的完整 schema 校验由 RoomDaoTest 的 v2 内存库覆盖。
 */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {

    @Test
    fun migrationCreatesTransferEventsTableAndIndexes() {        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "yadown-migration-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        val db = helper.writableDatabase

        try {
            YadownDatabase.MIGRATION_1_2.migrate(db)

            db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='transfer_events'"
            ).use { cursor ->
                assertTrue("transfer_events 表未创建", cursor.moveToFirst())
            }

            db.query(
                "SELECT name FROM sqlite_master WHERE type='index' " +
                    "AND tbl_name='transfer_events'"
            ).use { cursor ->
                val names = buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
                assertTrue(names.contains("index_transfer_events_intent_id"))
                assertTrue(names.contains("index_transfer_events_event"))
                assertTrue(names.contains("index_transfer_events_created_at"))
            }

            db.execSQL(
                "INSERT INTO transfer_events " +
                    "(intent_id, event, cause, bytes, elapsed_ms, created_at) " +
                    "VALUES ('i1', 'COMPLETED', NULL, 10, 100, 1)"
            )
            db.query("SELECT COUNT(*) FROM transfer_events").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    @Test
    fun migration2To3AddsMirrorAndLatencyColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "yadown-migration-2-3-${System.nanoTime()}.db"
        context.deleteDatabase(dbName)

        val callback = object : SupportSQLiteOpenHelper.Callback(2) {
            override fun onCreate(db: SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(callback)
                .build()
        )
        val db = helper.writableDatabase

        try {
            db.execSQL(
                """
                CREATE TABLE `intents` (
                    `id` TEXT NOT NULL,
                    `content_id` TEXT NOT NULL,
                    `source_url` TEXT NOT NULL,
                    `total_size` INTEGER,
                    `mime_type` TEXT,
                    `etag` TEXT,
                    `last_modified` TEXT,
                    `range_supported` INTEGER NOT NULL,
                    `value_map_json` TEXT,
                    `availability_contract_json` TEXT,
                    `state` TEXT NOT NULL,
                    `created_at` INTEGER NOT NULL,
                    `updated_at` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE `source_health` (
                    `source_key` TEXT NOT NULL,
                    `ewma_throughput` REAL NOT NULL,
                    `ewma_failure_rate` REAL NOT NULL,
                    `connection_limit_learned` INTEGER,
                    `last_updated` INTEGER NOT NULL,
                    `ttl_expires_at` INTEGER NOT NULL,
                    PRIMARY KEY(`source_key`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO intents (id, content_id, source_url, total_size, range_supported, " +
                    "state, created_at, updated_at) " +
                    "VALUES ('i1', 'c1', 'https://example.com/f', 10, 1, 'ACTIVE', 1, 1)"
            )
            db.execSQL(
                "INSERT INTO source_health (source_key, ewma_throughput, ewma_failure_rate, " +
                    "connection_limit_learned, last_updated, ttl_expires_at) " +
                    "VALUES ('example.com/f', 100.0, 0.0, 2, 1, 9999999999999)"
            )

            YadownDatabase.MIGRATION_2_3.migrate(db)

            assertTrue(columnsOf(db, "intents").contains("mirror_urls_json"))
            assertTrue(columnsOf(db, "source_health").contains("ewma_latency"))
            db.query("SELECT mirror_urls_json FROM intents").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            db.query("SELECT ewma_latency FROM source_health").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0.0, cursor.getDouble(0), 0.0)
            }
        } finally {
            helper.close()
            context.deleteDatabase(dbName)
        }
    }

    private fun columnsOf(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
}
