package whaleui.nightsky.ltd.data

import me.rerere.rikkahub.data.model.Conversation
import whaleui.nightsky.ltd.domain.chat.ChatListRepository
import whaleui.nightsky.ltd.domain.chat.ConversationItem
import whaleui.nightsky.ltd.whale.WhaleConversationProvider

/**
 * 抽屉的对话列表，由 RikkaHub 的 `ConversationRepository` 支撑。
 *
 * 阶段 1 只有一个固定对话（任务书 1.3），所以列表里永远就这一条。
 * 真正的多对话列表放在阶段 8。
 */
class RikkaChatListRepository(
    private val conversations: WhaleConversationProvider,
) : ChatListRepository {

    override suspend fun loadConversations(): List<ConversationItem> =
        listOf(conversations.fixedConversation().toItem())

    /** 只有一个对话，新建退回同一个。 */
    override suspend fun createConversation(title: String): ConversationItem =
        conversations.fixedConversation().toItem()

    /**
     * 界面改名 / 置顶之后会回调这里。阶段 1 不落库：
     * 抽屉只有一条，改名和置顶在阶段 8 接真实的 `ConversationRepository.updateTitle`。
     */
    override suspend fun saveConversations(items: List<ConversationItem>) = Unit

    private fun Conversation.toItem(): ConversationItem = ConversationItem(
        id = FIXED_CONVERSATION_ID,
        title = displayTitle(title),
        lastUpdatedAt = updateAt.toEpochMilli(),
        pinnedAt = if (isPinned) createAt.toEpochMilli() else null,
    )

    companion object {
        /** 界面上那个固定对话的 id（`ChatViewModel` 里默认打开 1L）。 */
        const val FIXED_CONVERSATION_ID = 1L

        /**
         * 库里的对话标题 → 列表 / 导出用的标题。空标题和改名前落库的旧标题「小鲸鱼」都换成现在的 App 名，
         * 导出的聊天文件因此叫 `chat/<日期>_miniWhale.md`；用户自己的标题原样保留。
         */
        fun displayTitle(stored: String): String =
            if (stored.isBlank() || stored.trim() == WhaleConversationProvider.LEGACY_CONVERSATION_TITLE) {
                WhaleConversationProvider.CONVERSATION_TITLE
            } else {
                stored
            }
    }
}
