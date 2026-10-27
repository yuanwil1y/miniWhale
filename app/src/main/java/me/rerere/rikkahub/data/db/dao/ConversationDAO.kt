package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import me.rerere.rikkahub.data.db.entity.ConversationEntity

/**
 * R2b：只留 [me.rerere.rikkahub.data.repository.ConversationRepository] 还在用的查询。
 * 原版会话列表 / 分页 / 标题搜索 / 置顶 / 文件夹 / 统计等查询随页面删掉（不影响表结构和 Room identity hash）。
 */
@Dao
interface ConversationDAO {
    @Query(
        "SELECT * FROM conversationentity WHERE assistant_id = :assistantId ORDER BY is_pinned DESC, update_at DESC LIMIT :limit"
    )
    suspend fun getRecentConversationsOfAssistant(assistantId: String, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversationentity WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM conversationentity WHERE id = :id)")
    suspend fun existsById(id: String): Boolean

    @Insert
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("UPDATE conversationentity SET assistant_id = :assistantId, folder_id = '' WHERE id = :id")
    suspend fun updateAssistantId(id: String, assistantId: String)

    @Query(
        "UPDATE conversationentity SET config = :config, mode_injection_ids = :modeInjectionIds, lorebook_ids = :lorebookIds, workspace_cwd = :workspaceCwd WHERE id = :id"
    )
    suspend fun updateConfig(
        id: String,
        config: String,
        modeInjectionIds: String,
        lorebookIds: String,
        workspaceCwd: String,
    )
}
