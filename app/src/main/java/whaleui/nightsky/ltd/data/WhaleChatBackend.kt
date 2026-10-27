package whaleui.nightsky.ltd.data

import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import whaleui.nightsky.ltd.whale.WhaleConversationProvider
import kotlin.uuid.Uuid

/**
 * [RikkaChatRepository] 用到的那一小部分 [ChatService]。抽成接口是为了让仓库能用假实现做 JVM 单测
 * （`RikkaChatRepositoryTest`）；线上只有 [ChatServiceBackend] 一个实现。
 */
interface WhaleChatBackend {
    /** 她那个固定对话的 id */
    suspend fun conversationId(): Uuid

    suspend fun getConversation(conversationId: Uuid): Conversation?

    fun conversationFlow(conversationId: Uuid): Flow<Conversation>

    /** 正在跑的生成任务；没有时为 null */
    fun generationJobFlow(conversationId: Uuid): Flow<Job?>

    /** 发出他的一条消息，返回入库的消息 id（见 [ChatService.sendMessage]） */
    fun sendMessage(conversationId: Uuid, parts: List<UIMessagePart>): Uuid?

    suspend fun deleteMessageEverywhere(conversationId: Uuid, messageId: Uuid)

    /** 原地替换一条消息的内容（多图消息删掉其中一张，F5） */
    suspend fun replaceMessageParts(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>)

    suspend fun appendDebugTurn(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        assistantParts: List<List<UIMessagePart>>,
    )
}

class ChatServiceBackend(
    private val chatService: ChatService,
    private val conversations: WhaleConversationProvider,
) : WhaleChatBackend {
    override suspend fun conversationId(): Uuid = conversations.fixedConversation().id

    override suspend fun getConversation(conversationId: Uuid): Conversation? =
        chatService.getConversation(conversationId)

    override fun conversationFlow(conversationId: Uuid): Flow<Conversation> =
        chatService.getConversationFlow(conversationId)

    override fun generationJobFlow(conversationId: Uuid): Flow<Job?> =
        chatService.getGenerationJobStateFlow(conversationId)

    override fun sendMessage(conversationId: Uuid, parts: List<UIMessagePart>): Uuid? =
        chatService.sendMessage(conversationId, parts)

    override suspend fun deleteMessageEverywhere(conversationId: Uuid, messageId: Uuid) =
        chatService.deleteMessageEverywhere(conversationId, messageId)

    override suspend fun replaceMessageParts(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) =
        chatService.replaceMessagePartsEverywhere(conversationId, messageId, parts)

    override suspend fun appendDebugTurn(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        assistantParts: List<List<UIMessagePart>>,
    ) = chatService.appendDebugTurn(conversationId, userParts, assistantParts)
}
