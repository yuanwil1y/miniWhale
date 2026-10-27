package whaleui.nightsky.ltd.ui.drawer

import androidx.compose.runtime.Immutable
import whaleui.nightsky.ltd.domain.chat.ConversationItem

/**
 * 当前对话的来源。抽屉里的「最近对话」列表、搜索、置顶 / 改名 / 删除在 C 阶段删掉了（Q4、01 X3–X5、D5）：
 * 现在只有和她的一个对话，这里只负责给出它的 id，聊天页按 [currentId] 加载消息。
 */
@Immutable
data class ChatListUiState(
    val conversations: List<ConversationItem> = emptyList(),
    val currentId: Long? = null,
    val isLoading: Boolean = false,
)

/** 排序：置顶在前，置顶的按置顶时间倒序；其余按最后更新时间倒序。 */
internal fun sortConversations(items: List<ConversationItem>): List<ConversationItem> =
    items.sortedWith(
        compareByDescending<ConversationItem> { it.isPinned }
            .thenByDescending { it.pinnedAt ?: Long.MIN_VALUE }
            .thenByDescending { it.lastUpdatedAt }
            .thenByDescending { it.id },
    )
