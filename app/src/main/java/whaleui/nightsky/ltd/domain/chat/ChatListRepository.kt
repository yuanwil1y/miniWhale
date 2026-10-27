package whaleui.nightsky.ltd.domain.chat

import androidx.compose.runtime.Immutable

import java.util.concurrent.atomic.AtomicLong

/**
 * 历史对话数据源。以后换真实存储（数据库 / 文件）时只替换这一层，
 * ViewModel 和抽屉界面不用改。
 */
interface ChatListRepository {
    suspend fun loadConversations(): List<ConversationItem>

    /** 新建一条空对话并返回它；具体 id / 时间由数据层生成。 */
    suspend fun createConversation(title: String): ConversationItem

    /** 保存完整列表。假数据源里就是替换内存中的列表。 */
    suspend fun saveConversations(items: List<ConversationItem>)
}

/**
 * 抽屉里的一条历史对话。
 *
 * 这一层是抽屉自己的结构（Telegram 当前版本没有对应实现），字段按需求定义：
 * id / 标题 / 最后更新时间 / 是否置顶 / 置顶时间。是否置顶由 [pinnedAt] 是否为空推导，
 * 避免两个字段不同步。
 */
@Immutable
data class ConversationItem(
    val id: Long,
    val title: String,
    /** 最后一条消息的时间，毫秒时间戳；用来排“最近”。 */
    val lastUpdatedAt: Long,
    /** 置顶时间，毫秒时间戳；null 表示未置顶。 */
    val pinnedAt: Long? = null,
) {
    val isPinned: Boolean get() = pinnedAt != null
}
