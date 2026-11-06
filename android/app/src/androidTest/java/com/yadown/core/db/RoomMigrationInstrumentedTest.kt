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
    fun migrationCreatesTransferEventsTableAndIndexes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
}
