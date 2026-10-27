package whaleui.nightsky.ltd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 表情系列（任务书 3.1，`sticker_pack` 表）。主键就是系列号，例如 `01`。
 *
 * 列表型字段（`characters`）用逗号分隔的文本存，读出来再拆——和工程里
 * `schedule_task.repeat_days` 的做法一致，不需要额外的 TypeConverter。
 */
@Entity(tableName = "sticker_pack")
data class StickerPackEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    @ColumnInfo("title_original")
    val titleOriginal: String,
    val characters: String,
    val animated: Boolean,
    @ColumnInfo("has_sound_originally")
    val hasSoundOriginally: Boolean,
    val source: String,
    @ColumnInfo("license_note")
    val licenseNote: String,
    @ColumnInfo("sort_order")
    val sortOrder: Int,
    /** 她能不能用；默认 true。关掉后 find_sticker / send_sticker 都排除这个系列。 */
    @ColumnInfo("enabled_for_her")
    val enabledForHer: Boolean = true,
    @ColumnInfo("imported_at")
    val importedAt: Long,
)

/**
 * 一张表情（任务书 3.1，`sticker` 表）。主键是对外编号，例如 `01-1000001`。
 *
 * [filePath] / [thumbPath] 存**相对 `filesDir`** 的路径（`stickers/01/xxx.webp`），
 * 这样 App 数据目录整体搬走也不会坏。删系列时靠外键 CASCADE 连带删贴图行，
 * 文件由 `RoomStickerRepository` 一起删。
 */
@Entity(
    tableName = "sticker",
    foreignKeys = [
        ForeignKey(
            entity = StickerPackEntity::class,
            parentColumns = ["id"],
            childColumns = ["pack_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("pack_id")],
)
data class StickerEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo("pack_id")
    val packId: String,
    @ColumnInfo("file_path")
    val filePath: String,
    @ColumnInfo("thumb_path")
    val thumbPath: String,
    /** 固定 `webp`。 */
    val format: String,
    val animated: Boolean,
    /** ≤ 12 字，"谁在做什么 / 什么心情"。 */
    val description: String,
    /** 30–90 字，她挑表情时主要看这一条。 */
    val detail: String,
    @ColumnInfo("text_in_image")
    val textInImage: String,
    @ColumnInfo("text_meaning")
    val textMeaning: String,
    val characters: String,
    val couple: Boolean,
    /** 逗号分隔，1–3 个 42 词表里的词。 */
    val emotions: String,
    @ColumnInfo("use_when")
    val useWhen: String,
    @ColumnInfo("needs_review")
    val needsReview: Boolean,
    @ColumnInfo("last_sent_by_her_at")
    val lastSentByHerAt: Long?,
    @ColumnInfo("sent_by_her_count")
    val sentByHerCount: Int,
)
