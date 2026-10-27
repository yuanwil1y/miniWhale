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
 * 34 → 35 的迁移测试（round5 3.1）：确认两张新表（`sticker_pack` / `sticker`）建出来了、
 * 能插入能读出（可空列写 NULL 读回来还是 NULL），外键删除会级联，v34 已有的表和数据没被迁移动到。
 *
 * schema 从 `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/` 打包进 androidTest 的
 * assets（见 `app/build.gradle.kts` 的 sourceSets）。
 */
@RunWith(AndroidJUnit4::class)
class WhaleStickerMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        RequerySQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate34To35_createsStickerTables() {
        val testDb = "whale-migration-34-35-test"
        helper.createDatabase(testDb, 34).apply {
            execSQL(
                "INSERT INTO memory_item (id, content, category, origin, created_at, updated_at, sort_index) " +
                    "VALUES ('mem-before-migration', '旧的记忆', 'AboutYou', 'UserAdded', 1000, 1000, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 35, true)

        val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("迁移后应该有 sticker_pack 表，实际表：$tables", tables.contains("sticker_pack"))
        assertTrue("迁移后应该有 sticker 表，实际表：$tables", tables.contains("sticker"))
        assertEquals(0, countTable(db, "sticker_pack"))
        assertEquals(0, countTable(db, "sticker"))

        db.query("SELECT content FROM memory_item WHERE id = 'mem-before-migration'").use { cursor ->
            assertTrue("v34 已有的记忆应该还在", cursor.moveToFirst())
            assertEquals("旧的记忆", cursor.getString(0))
        }

        db.close()
    }

    @Test
    fun migrate34To35_stickerRowsRoundTrip() {
        val testDb = "whale-migration-34-35-roundtrip-test"
        helper.createDatabase(testDb, 34).apply { close() }
        val db = helper.runMigrationsAndValidate(testDb, 35, true)

        db.insert(
            "sticker_pack",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "01")
                put("name", "冬季约会")
                put("title_original", "示例系列（原名）")
                put("characters", "角色甲,角色乙")
                put("animated", 1)
                put("has_sound_originally", 0)
                put("source", "示例贴图(用户自有)")
                put("license_note", "仅个人使用,不得分发")
                put("sort_order", 0)
                put("enabled_for_her", 1)
                put("imported_at", 1_700_000_000_000L)
            },
        )
        db.insert(
            "sticker",
            SQLiteDatabase.CONFLICT_NONE,
            ContentValues().apply {
                put("id", "01-1000001")
                put("pack_id", "01")
                put("file_path", "stickers/01/1000001.webp")
                put("thumb_path", "stickers/_thumbs/01-1000001.png")
                put("format", "webp")
                put("animated", 1)
                put("description", "抱在一起")
                put("detail", "测试表情的详细说明。")
                put("text_in_image", "")
                put("text_meaning", "")
                put("characters", "角色甲,角色乙")
                put("couple", 1)
                put("emotions", "抱抱,爱你")
                put("use_when", "测试场景")
                put("needs_review", 0)
                // last_sent_by_her_at 省略 → NULL
                put("sent_by_her_count", 0)
            },
        )

        db.query(
            "SELECT name, title_original, characters, enabled_for_her FROM sticker_pack WHERE id = '01'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("冬季约会", cursor.getString(0))
            assertEquals("角色甲,角色乙", cursor.getString(2))
            assertEquals(1, cursor.getInt(3))
        }
        db.query(
            "SELECT file_path, description, emotions, last_sent_by_her_at, sent_by_her_count " +
                "FROM sticker WHERE id = '01-1000001'"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("stickers/01/1000001.webp", cursor.getString(0))
            assertEquals("抱在一起", cursor.getString(1))
            assertEquals("抱抱,爱你", cursor.getString(2))
            assertTrue("没发过的 last_sent_by_her_at 应该是 NULL", cursor.isNull(3))
            assertEquals(0, cursor.getInt(4))
        }

        // 外键 CASCADE：删系列连带删贴图行。
        // MigrationTestHelper 的裸连接默认没开 foreign_keys，Room 运行时会自己开，这里显式开一下。
        db.execSQL("PRAGMA foreign_keys = ON")
        db.delete("sticker_pack", "id = ?", arrayOf("01"))
        assertEquals(0, countTable(db, "sticker"))
        db.close()
    }

    private fun countTable(db: SupportSQLiteDatabase, table: String): Int =
        db.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
}
