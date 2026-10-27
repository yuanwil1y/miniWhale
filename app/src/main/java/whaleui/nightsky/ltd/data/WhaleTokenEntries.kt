package whaleui.nightsky.ltd.data

import kotlinx.datetime.toJavaLocalDate
import me.rerere.rikkahub.data.repository.ConversationRepository
import whaleui.nightsky.ltd.ui.profile.TokenEntrySource
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.whale.WhaleRuntimeStore
import java.time.LocalDate

/**
 * 资料页 token 热力图的数据（B）：她名下全部对话的全部消息（每个节点的所有分支都算——重新生成也真花了 token）。
 *
 * 走现成的 [ConversationRepository.getRecentConversations]（按页读 message_node 并解 JSON），不加查询、不改表。
 * 试过直接用 DAO 分页 + 只解 createdAt / usage 两个字段：模拟器上 5600 条消息同样约 1.2 秒，没有更快，就不另写一套。
 * `createdAt` 本来就是设备本地时区的 LocalDateTime，直接取日期。
 */
class WhaleTokenEntries(
    private val conversations: ConversationRepository,
    private val runtimeStore: WhaleRuntimeStore,
) : TokenEntrySource {

    override suspend fun entries(): Sequence<Pair<LocalDate, Int?>> {
        val assistantId = WhaleGate.assistantId ?: runtimeStore.whaleAssistantId ?: return emptySequence()
        val all = conversations.getRecentConversations(assistantId, limit = MAX_CONVERSATIONS)
        return all.asSequence()
            .flatMap { it.messageNodes.asSequence() }
            .flatMap { it.messages.asSequence() }
            .map { it.createdAt.date.toJavaLocalDate() to it.usage?.totalTokens }
    }

    private companion object {
        /** 现在只有一个固定对话；给个宽松上限 */
        const val MAX_CONVERSATIONS = 1000
    }
}
