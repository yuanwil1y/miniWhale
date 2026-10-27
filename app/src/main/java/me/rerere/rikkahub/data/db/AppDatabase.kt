package me.rerere.rikkahub.data.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import me.rerere.ai.core.TokenUsage
import me.rerere.rikkahub.data.db.dao.ConversationDAO
import me.rerere.rikkahub.data.db.dao.FavoriteDAO
import me.rerere.rikkahub.data.db.dao.ManagedFileDAO
import me.rerere.rikkahub.data.db.dao.MemoryDAO
import me.rerere.rikkahub.data.db.dao.MessageNodeDAO
import me.rerere.rikkahub.data.db.dao.UsageRecordDAO
import me.rerere.rikkahub.data.db.entity.ConversationEntity
import me.rerere.rikkahub.data.db.entity.FavoriteEntity
import me.rerere.rikkahub.data.db.entity.FolderEntity
import me.rerere.rikkahub.data.db.entity.GenMediaEntity
import me.rerere.rikkahub.data.db.entity.ManagedFileEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationNodeEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationRecordEntity
import me.rerere.rikkahub.data.db.entity.MediaCreationSessionEntity
import me.rerere.rikkahub.data.db.entity.MemoryConsolidationEntity
import me.rerere.rikkahub.data.db.entity.MemoryFileEntity
import me.rerere.rikkahub.data.db.entity.MessageNodeEntity
import me.rerere.rikkahub.data.db.entity.TranslationHistoryEntity
import me.rerere.rikkahub.data.db.entity.UsageRecordEntity
import me.rerere.rikkahub.data.db.entity.WorkspaceEntity
import me.rerere.rikkahub.data.db.migrations.Migration_16_17
import me.rerere.rikkahub.data.db.migrations.Migration_22_23
import me.rerere.rikkahub.data.db.migrations.Migration_28_29
import me.rerere.rikkahub.data.db.migrations.Migration_8_9
import me.rerere.rikkahub.utils.JsonInstant
import whaleui.nightsky.ltd.data.db.DiaryEntryEntity
import whaleui.nightsky.ltd.data.db.HerAlarmEntity
import whaleui.nightsky.ltd.data.db.MemoryItemEntity
import whaleui.nightsky.ltd.data.db.ScheduleTaskEntity
import whaleui.nightsky.ltd.data.db.StickerEntity
import whaleui.nightsky.ltd.data.db.StickerPackEntity
import whaleui.nightsky.ltd.data.db.WhaleDiaryDao
import whaleui.nightsky.ltd.data.db.WhaleHerAlarmDao
import whaleui.nightsky.ltd.data.db.WhaleMemoryDao
import whaleui.nightsky.ltd.data.db.WhaleScheduleDao
import whaleui.nightsky.ltd.data.db.WhaleStickerDao

@Database(
    entities = [
        ConversationEntity::class,
        MemoryFileEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（原生记忆整理，S11b）
        MemoryConsolidationEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（原生作图记录，S7）
        GenMediaEntity::class,
        MessageNodeEntity::class,
        ManagedFileEntity::class,
        FavoriteEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（:workspace，S6）
        WorkspaceEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（原版抽屉的会话文件夹，S11；conversation.folder_id 列照旧保存）
        FolderEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（媒体创作三张表，S7）
        MediaCreationSessionEntity::class,
        MediaCreationNodeEntity::class,
        MediaCreationRecordEntity::class,
        // 保留不删（R2）：已无读写代码；删表要改版本号、写迁移，保留比迁移风险小（翻译页，S7）
        TranslationHistoryEntity::class,
        UsageRecordEntity::class,
        MemoryItemEntity::class,
        DiaryEntryEntity::class,
        ScheduleTaskEntity::class,
        HerAlarmEntity::class,
        StickerPackEntity::class,
        StickerEntity::class,
    ],
    version = 35,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9, spec = Migration_8_9::class),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 12, to = 13),
        AutoMigration(from = 16, to = 17, spec = Migration_16_17::class),
        AutoMigration(from = 17, to = 18),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 21, to = 22),
        AutoMigration(from = 22, to = 23, spec = Migration_22_23::class),
        AutoMigration(from = 23, to = 24),
        AutoMigration(from = 24, to = 25),
        AutoMigration(from = 25, to = 26),
        AutoMigration(from = 26, to = 27),
        AutoMigration(from = 28, to = 29, spec = Migration_28_29::class),
        AutoMigration(from = 29, to = 30),
        AutoMigration(from = 31, to = 32),
        AutoMigration(from = 32, to = 33),
        AutoMigration(from = 33, to = 34),
        AutoMigration(from = 34, to = 35),
    ]
)
@TypeConverters(TokenUsageConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDAO

    abstract fun memoryDao(): MemoryDAO

    abstract fun messageNodeDao(): MessageNodeDAO

    abstract fun managedFileDao(): ManagedFileDAO

    abstract fun favoriteDao(): FavoriteDAO

    abstract fun usageRecordDao(): UsageRecordDAO

    abstract fun whaleMemoryDao(): WhaleMemoryDao

    abstract fun whaleDiaryDao(): WhaleDiaryDao

    abstract fun whaleScheduleDao(): WhaleScheduleDao

    abstract fun whaleHerAlarmDao(): WhaleHerAlarmDao

    abstract fun whaleStickerDao(): WhaleStickerDao
}

object TokenUsageConverter {
    @TypeConverter
    fun fromTokenUsage(usage: TokenUsage?): String {
        return JsonInstant.encodeToString(usage)
    }

    @TypeConverter
    fun toTokenUsage(usage: String): TokenUsage? {
        return JsonInstant.decodeFromString(usage)
    }
}
