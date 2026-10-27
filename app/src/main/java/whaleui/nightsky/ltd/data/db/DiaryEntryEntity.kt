package whaleui.nightsky.ltd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一篇日记。每天一行（[date] 唯一索引），[content] 由多段 `[HH:mm] 文本` 追加而成，
 * 与 Hermes 的日记文件格式一致。
 *
 * [date] 存 `yyyy-MM-dd` 字符串，SQLite 里按字典序倒序排就是日期倒序。
 * [weather] 允许空字符串（现在还没有天气来源）。
 */
@Entity(
    tableName = "diary_entry",
    indices = [Index(value = ["date"], unique = true)],
)
data class DiaryEntryEntity(
    @PrimaryKey
    val id: String,
    val date: String,
    val content: String,
    val weather: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
)
