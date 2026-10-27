package whaleui.nightsky.ltd.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.service.ConversationSessionManager
import me.rerere.rikkahub.service.FakeConversationStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatEvent
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageIds
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import whaleui.nightsky.ltd.ui.chat.unusedUploads
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

/**
 * W-022：她刚说完（排着 5 秒后的空闲回收）他就发消息，会话对象在 7 秒批次到点前被回收，
 * 到点时新建了另一个会话对象来跑这一轮。发送流程要能看到新对象上的流式更新，不能一直盯着旧对象。
 *
 * 仓库接的是真实的 [ConversationSessionManager]（只有落库是内存假实现），这样测的是线上那条订阅链路。
 */
class SessionRecycleSendTest {

    @Test
    fun `streaming reply reaches the UI when the session was evicted and recreated before the batch fired`() = runTest {
        val id = Uuid.random()
        val store = FakeConversationStore(
            Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("早"), her("早上好"))),
        )
        // 这里不留待发批次（isRetained 默认 false），专门制造「被回收后重建」，看订阅是否跟得上
        val manager = ConversationSessionManager(
            scope = backgroundScope,
            store = store,
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { _, _ -> },
        )
        val repository = RikkaChatRepository(
            ManagerBackend(id, manager, store),
            debugCommands = NoCommands,
            io = EmptyCoroutineContext,
            uploads = unusedUploads(),
        )

        // 她上一轮刚结束：会话 S 排上 5 秒后的空闲回收
        val first = manager.getOrCreate(id)
        first.initialize(load = { store.persisted })
        first.setJob(backgroundScope.launch(start = CoroutineStart.LAZY) { })
        runCurrent()
        advanceTimeBy(3_000)

        // 3 秒后他发一条
        val events = mutableListOf<ChatEvent>()
        val outgoing = ChatMessage(id = 10_001, fromHer = false, text = "在吗", time = "08:00", status = MessageStatus.Sending)
        val sending = backgroundScope.launch { repository.send(1L, listOf(outgoing)).collect { events += it } }
        runCurrent()

        // 第 5 秒：S 被回收（先落库）
        advanceTimeBy(2_500)
        runCurrent()

        // 第 7 秒批次到点：新建会话 S2 从库里加载，开始这一轮
        val second = manager.getOrCreate(id)
        assertNotSame("前提：会话已经换成了另一个对象", first, second)
        second.initialize(load = { store.persisted })
        val turn = backgroundScope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
        second.setJob(turn)
        runCurrent()

        val reply = her("在的")
        second.update { it.copy(messageNodes = it.messageNodes + reply.toMessageNode()) }
        runCurrent()

        assertEquals(
            "这一轮还在生成，流式内容就要到界面，不能等结束补显",
            listOf(MessageIds.forUuid(reply.id)),
            events.filterIsInstance<ChatEvent.Added>().map { it.message.id },
        )
        val streamed = reply.copy(parts = listOf(UIMessagePart.Text("在的，刚醒")))
        second.update { c ->
            c.copy(messageNodes = c.messageNodes.map { node ->
                if (node.messages.any { it.id == reply.id }) node.copy(messages = listOf(streamed)) else node
            })
        }
        runCurrent()
        assertEquals(
            listOf(MessageIds.forUuid(reply.id)),
            events.filterIsInstance<ChatEvent.Updated>().map { it.message.id }.distinct(),
        )

        turn.cancel()
        sending.cancel()
    }

    private fun her(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))

    private object NoCommands : DebugChatCommands {
        override fun isCommand(rawText: String) = false
        override suspend fun reply(rawText: String): DebugTurn? = null
    }

    /** 线上 [ChatServiceBackend] 的样子：内容、生成任务都按 id 从会话注册表取 */
    private class ManagerBackend(
        private val id: Uuid,
        private val manager: ConversationSessionManager,
        private val store: FakeConversationStore,
    ) : WhaleChatBackend {
        override suspend fun conversationId(): Uuid = id

        override suspend fun getConversation(conversationId: Uuid): Conversation? =
            manager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value ?: store.persisted

        override fun conversationFlow(conversationId: Uuid): Flow<Conversation> =
            manager.getConversationFlow(conversationId)

        override fun generationJobFlow(conversationId: Uuid): Flow<Job?> =
            manager.getGenerationJobStateFlow(conversationId)

        // 和修复前的 ChatService.sendMessage 一样只 getOrCreate（不持有引用），落库交给回收前的 flush
        override fun sendMessage(conversationId: Uuid, parts: List<UIMessagePart>): Uuid {
            val message = UIMessage(role = MessageRole.USER, parts = parts)
            manager.getOrCreate(conversationId).update { it.copy(messageNodes = it.messageNodes + message.toMessageNode()) }
            return message.id
        }

        override suspend fun deleteMessageEverywhere(conversationId: Uuid, messageId: Uuid) = error("not used")

        override suspend fun replaceMessageParts(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) =
            error("not used")

        override suspend fun appendDebugTurn(
            conversationId: Uuid,
            userParts: List<UIMessagePart>,
            assistantParts: List<List<UIMessagePart>>,
        ) = error("not used")
    }
}
