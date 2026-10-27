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
 * 32 → 33 的迁移测试：确认两张新表（`memory_item` / `diary_entry`）建出来了，
 * 能插入、能读出，并且 RikkaHub 原有的表和已有数据没被迁移动到。
 *
 * schema 从 `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/` 打包进 androidTest
 * 的 assets（见 `app/build.gradle.kts` 的 sourceSets）。
 */
@RunWith(AndroidJUnit4::class)
class WhaleMigrationTest {

    private val testDb = "whale-migration-32-33-test"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate32To33_createsMemoryItemAndDiaryEntryTables() {
        helper.createDatabase(testDb, 32).apply {
            // 迁移前就有的对话：迁移后必须原样还在
            execSQL(
                "INSERT INTO ConversationEntity (id, assistant_id, title, nodes, create_at, update_at) " +
                    "VALUES ('conv-before-migration', 'assistant', '旧数据', '[]', 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 33, true)

        // 1. 两张新表存在，且刚迁移过来是空表
        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("迁移后应该有 memory_item 表，实际表：$tables", tables.contains("memory_item"))
        assertTrue("迁移后应该有 diary_entry 表，实际表：$tables", tables.contains("diary_entry"))
        assertEquals(0, countTable(db, "memory_item"))
        assertEquals(0, countTable(db, "diary_entry"))

        // 2. 原有表和原有数据没被改动
        db.query("SELECT title FROM ConversationEntity WHERE id = 'conv-before-migration'").use { cursor ->
            assertTrue("32 版已有的对话应该还在", cursor.moveToFirst())
            assertEquals("旧数据", cursor.getString(0))
        }

        // 3. 日记的 date 唯一索引建出来了（每天一行）
        val diaryIndexes = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'diary_entry'"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue(
            "diary_entry 应该有 date 唯一索引，实际索引：$diaryIndexes",
            diaryIndexes.contains("index_diary_entry_date"),
        )

        db.close()
    }

    @Test
    fun migrate32To33_canInsertAndReadNewTables() {
        helper.createDatabase(testDb, 32).apply { close() }

        val db = helper.runMigrationsAndValidate(testDb, 33, true)

        // memory_item：按契约字段写一行再读回来
        val memoryValues = ContentValues().apply {
            put("id", "memory-1")
            put("content", "测试记忆内容")
            put("category", "AboutYou")
            put("origin", "HerRecorded")
            put("created_at", 1_700_000_000_000L)
            put("updated_at", 1_700_000_000_500L)
            put("sort_index", 7L)
        }
        assertEquals(1L, db.insert("memory_item", SQLiteDatabase.CONFLICT_NONE, memoryValues))

        db.query(
            "SELECT id, content, category, origin, created_at, updated_at, sort_index " +
                "FROM memory_item WHERE id = 'memory-1'"
        ).use { cursor ->
            assertTrue("memory_item 应该能读到刚插入的行", cursor.moveToFirst())
            assertEquals("memory-1", cursor.getString(0))
            assertEquals("测试记忆内容", cursor.getString(1))
            assertEquals("AboutYou", cursor.getString(2))
            assertEquals("HerRecorded", cursor.getString(3))
            assertEquals(1_700_000_000_000L, cursor.getLong(4))
            assertEquals(1_700_000_000_500L, cursor.getLong(5))
            assertEquals(7L, cursor.getLong(6))
        }

        // diary_entry：日期是 yyyy-MM-dd 文本，weather 允许空串
        val diaryValues = ContentValues().apply {
            put("id", "diary-1")
            put("date", "2026-10-26")
            put("content", "[21:30] 测试日记内容。")
            put("weather", "")
            put("created_at", 1_700_000_000_000L)
            put("updated_at", 1_700_000_000_000L)
        }
        assertEquals(1L, db.insert("diary_entry", SQLiteDatabase.CONFLICT_NONE, diaryValues))

        db.query(
            "SELECT id, date, content, weather FROM diary_entry WHERE date = '2026-10-26'"
        ).use { cursor ->
            assertTrue("diary_entry 应该能读到刚插入的行", cursor.moveToFirst())
            assertEquals("diary-1", cursor.getString(0))
            assertEquals("2026-10-26", cursor.getString(1))
            assertEquals("[21:30] 测试日记内容。", cursor.getString(2))
            assertEquals("", cursor.getString(3))
        }

        db.close()
    }

    private fun countTable(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
