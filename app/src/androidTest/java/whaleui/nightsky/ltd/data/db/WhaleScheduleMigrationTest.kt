package whaleui.nightsky.ltd.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.requery.android.database.sqlite.RequerySQLiteOpenHelperFactory
import me.rerere.rikkahub.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 33 → 34 的迁移测试：确认两张新表（`schedule_task` / `her_alarm`）建出来了、
 * 能插入能读出（可空列写 NULL 读回来还是 NULL），并且 v33 已有的表和数据没被迁移动到。
 *
 * schema 从 `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/` 打包进 androidTest 的
 * assets（见 `app/build.gradle.kts` 的 sourceSets）。
 */
@RunWith(AndroidJUnit4::class)
class WhaleScheduleMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate33To34_createsScheduleTaskAndHerAlarmTables() {
        val testDb = "whale-migration-33-34-test"
        helper.createDatabase(testDb, 33).apply {
            // v33 已有的记忆：迁移后必须原样还在
            execSQL(
                "INSERT INTO memory_item (id, content, category, origin, created_at, updated_at, sort_index) " +
                    "VALUES ('mem-before-migration', '旧的记忆', 'AboutYou', 'UserAdded', 1000, 1000, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 34, true)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("迁移后应该有 schedule_task 表，实际表：$tables", tables.contains("schedule_task"))
        assertTrue("迁移后应该有 her_alarm 表，实际表：$tables", tables.contains("her_alarm"))
        assertEquals(0, countTable(db, "schedule_task"))
        assertEquals(0, countTable(db, "her_alarm"))

        db.query("SELECT content FROM memory_item WHERE id = 'mem-before-migration'").use { cursor ->
            assertTrue("v33 已有的记忆应该还在", cursor.moveToFirst())
            assertEquals("旧的记忆", cursor.getString(0))
        }

        db.close()
    }

    @Test
    fun migrate33To34_scheduleTaskColumnsRoundTrip() {
        val testDb = "whale-migration-33-34-schedule-test"
        helper.createDatabase(testDb, 33).apply { close() }
        val db = helper.runMigrationsAndValidate(testDb, 34, true)

        // 固定时间 + 每天：随机相关列、lastSentAt、todayPicked* 都省略（NULL），read 回来必须还是 NULL。
        db.insert(
            "schedule_task",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "task-fixed")
                put("title", "早上醒来")
                put("hint", "闹钟：早上了")
                put("timing_type", "fixed")
                put("time", "08:30")
                put("repeat_type", "daily")
                put("repeat_days", "")
                put("enabled", 1)
                put("sort_index", 1L)
            },
        )

        db.query(
            "SELECT id, title, hint, timing_type, time, window_start, window_end, repeat_type, " +
                "repeat_days, enabled, last_sent_at, today_picked_date, today_picked_time, sort_index " +
                "FROM schedule_task WHERE id = 'task-fixed'"
        ).use { cursor ->
            assertTrue("固定时间任务应该能读出来", cursor.moveToFirst())
            assertEquals("task-fixed", cursor.getString(0))
            assertEquals("早上醒来", cursor.getString(1))
            assertEquals("闹钟：早上了", cursor.getString(2))
            assertEquals("fixed", cursor.getString(3))
            assertEquals("08:30", cursor.getString(4))
            assertTrue("固定任务的 window_start 应该是 NULL", cursor.isNull(5))
            assertTrue("固定任务的 window_end 应该是 NULL", cursor.isNull(6))
            assertEquals("daily", cursor.getString(7))
            assertEquals("", cursor.getString(8))
            assertEquals(1, cursor.getInt(9))
            assertTrue("没发过的 last_sent_at 应该是 NULL", cursor.isNull(10))
            assertTrue("没挑过的 today_picked_date 应该是 NULL", cursor.isNull(11))
            assertTrue("没挑过的 today_picked_time 应该是 NULL", cursor.isNull(12))
            assertEquals(1L, cursor.getLong(13))
        }

        // 随机 + 自定义星期：窗口跨零点、已挑时间、已发送；hint 省略（NULL）。
        db.insert(
            "schedule_task",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "task-random")
                put("title", "想你了")
                put("timing_type", "random")
                put("window_start", "23:00")
                put("window_end", "01:00")
                put("repeat_type", "custom")
                put("repeat_days", "MONDAY,WEDNESDAY")
                put("enabled", 0)
                put("last_sent_at", 1_700_000_000_000L)
                put("today_picked_date", "2026-10-26")
                put("today_picked_time", "00:30")
                put("sort_index", 2L)
            },
        )

        db.query(
            "SELECT hint, timing_type, time, window_start, window_end, repeat_type, repeat_days, " +
                "enabled, last_sent_at, today_picked_date, today_picked_time, sort_index " +
                "FROM schedule_task WHERE id = 'task-random'"
        ).use { cursor ->
            assertTrue("随机任务应该能读出来", cursor.moveToFirst())
            assertTrue("没有 hint 的任务读回来应该是 NULL", cursor.isNull(0))
            assertEquals("random", cursor.getString(1))
            assertTrue("随机任务的 time 应该是 NULL", cursor.isNull(2))
            assertEquals("23:00", cursor.getString(3))
            assertEquals("01:00", cursor.getString(4))
            assertEquals("custom", cursor.getString(5))
            assertEquals("MONDAY,WEDNESDAY", cursor.getString(6))
            assertEquals(0, cursor.getInt(7))
            assertEquals(1_700_000_000_000L, cursor.getLong(8))
            assertEquals("2026-10-26", cursor.getString(9))
            assertEquals("00:30", cursor.getString(10))
            assertEquals(2L, cursor.getLong(11))
        }

        db.close()
    }

    @Test
    fun migrate33To34_herAlarmRowsRoundTrip() {
        val testDb = "whale-migration-33-34-alarm-test"
        helper.createDatabase(testDb, 33).apply { close() }
        val db = helper.runMigrationsAndValidate(testDb, 34, true)

        db.insert(
            "her_alarm",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "alarm-1")
                put("fire_at", 1_700_000_123_000L)
                put("note", "提醒他喝水")
                put("created_at", 1_700_000_000_000L)
                put("fired", 1)
            },
        )

        db.query(
            "SELECT id, fire_at, note, created_at, fired FROM her_alarm WHERE id = 'alarm-1'"
        ).use { cursor ->
            assertTrue("她的闹钟应该能读出来", cursor.moveToFirst())
            assertEquals("alarm-1", cursor.getString(0))
            assertEquals(1_700_000_123_000L, cursor.getLong(1))
            assertEquals("提醒他喝水", cursor.getString(2))
            assertEquals(1_700_000_000_000L, cursor.getLong(3))
            assertEquals(1, cursor.getInt(4))
        }

        db.close()
    }

    private fun countTable(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
