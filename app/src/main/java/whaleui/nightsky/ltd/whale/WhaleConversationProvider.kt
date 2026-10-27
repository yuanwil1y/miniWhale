package whaleui.nightsky.ltd.whale

import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ConversationRepository

/**
 * 我们那一个固定对话的解析器。
 *
 * 规则（任务书 1.3）：「固定使用一个对话：首次启动创建，之后复用」。
 * - 先用 [WhaleRuntimeStore] 里记的 id 去数据库找，找到就直接用；
 * - 找不到就建一个，绑到小鲸鱼助手上（阶段 2 之前 `WhaleGate.assistantId` 还是空，
 *   这时退化绑到 RikkaHub 当前助手，好让阶段 1 的调试命令能跑通）。
 *
 * 只有我们自己调用，不做缓存——`getConversationById` 本来就轻。
 */
class WhaleConversationProvider(
    private val conversationRepository: ConversationRepository,
    private val settingsStore: SettingsStore,
    private val runtimeStore: WhaleRuntimeStore,
) {

    @Volatile
    private var cached: Conversation? = null

    /** 拿到（必要时创建）固定对话，并把它登记到 [WhaleGate]。 */
    suspend fun fixedConversation(): Conversation {
        cached?.let { return it }
        val fromStore = runtimeStore.fixedConversationId
            ?.let { conversationRepository.getConversationById(it) }
        if (fromStore != null) return remember(fromStore)

        val existing = WhaleGate.conversationId
            ?.let { conversationRepository.getConversationById(it) }
        if (existing != null) return remember(existing)

        val assistantId = WhaleGate.assistantId
            ?: settingsStore.awaitLoaded().getCurrentAssistant().id
        val created = Conversation(
            assistantId = assistantId,
            title = CONVERSATION_TITLE,
            messageNodes = emptyList(),
        )
        conversationRepository.insertConversation(created)
        return remember(created)
    }

    /** 阶段 2 换了助手之后，把固定对话重新绑过去。 */
    suspend fun rebindAssistant(assistantId: kotlin.uuid.Uuid) {
        val current = fixedConversation()
        if (current.assistantId == assistantId) return
        // 只改 assistant_id 这一列（W-058）：缓存的是启动时那份会话，整份写回会把这期间新增的消息节点覆盖掉
        conversationRepository.updateConversationAssistant(current.id, assistantId)
        cached = conversationRepository.getConversationById(current.id) ?: current.copy(assistantId = assistantId)
    }

    private fun remember(conversation: Conversation): Conversation {
        cached = conversation
        WhaleGate.conversationId = conversation.id
        runtimeStore.fixedConversationId = conversation.id
        return conversation
    }

    companion object {
        /** 只有一个对话，标题固定（App 名）；将来抽屉多对话时再说。导出聊天文件名 `<日期>_<标题>.md` 用它。 */
        const val CONVERSATION_TITLE = "miniWhale"

        /** 改名前建的固定对话已经以这个标题落库；读出来时按 [CONVERSATION_TITLE] 处理，不改库。 */
        const val LEGACY_CONVERSATION_TITLE = "小鲸鱼"
    }
}
