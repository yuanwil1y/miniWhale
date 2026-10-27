package me.rerere.rikkahub.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentHooks
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.provider.TextGenerationResult
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.whale.WhaleGate
import kotlin.uuid.Uuid

/**
 * runTurn 的核心（[planReply] → agent → [applyReply] → finishGeneration → [previewIn] / [sentIn]），
 * 用真实的 AgentLoop、ConversationSession 和小鲸鱼切片跑，只有模型是假的。
 */
class ReplyTurnTest {
    private val conversationId = Uuid.random()
    private val assistantId = Uuid.random()
    private val model = Model(modelId = "fake")
    private val yesterday = LocalDateTime(2026, 10, 25, 22, 30)

    @Before
    fun setUp() {
        WhaleGate.assistantId = assistantId
        WhaleGate.conversationId = conversationId
    }

    @After
    fun tearDown() {
        WhaleGate.assistantId = null
        WhaleGate.conversationId = null
    }

    // ---------------------------------------------------------------- W-002

    @Test
    fun `alarm turn after her message opens a new message instead of continuing it`() = runBlocking {
        val him = user("晚安")
        val her = assistant("晚安呀")
        val session = session(him, her)
        val provider = FakeStreamProvider("消息1\n消息2")

        val (plan, finished) = runTurn(session, provider, forceNewNode = true)

        val messages = finished.currentMessages
        assertEquals(listOf(him.id, her.id), messages.take(2).map { it.id })
        assertEquals("她昨晚那条消息不能被改写", her, messages[1])
        val fresh = messages.drop(2)
        assertEquals(listOf("消息1", "消息2"), fresh.map { it.toText() })
        assertTrue(fresh.all { it.role == MessageRole.ASSISTANT && it.id != her.id })
        assertTrue("新消息不能沿用旧消息的时间", fresh.all { it.createdAt != her.createdAt })
        assertEquals(4, finished.messageNodes.size)
        // 请求内容和以前一样：历史原样交给模型（闹钟说明由 WhaleGuardTransformer 追加在末尾）
        assertEquals(listOf(him.id, her.id), provider.requests.single().map { it.id })
        assertTrue(plan.sentIn(finished))
        assertEquals("消息2", plan.previewIn(finished))
    }

    @Test
    fun `silent alarm turn leaves her old message alone and is not reported as sent`() = runBlocking {
        val him = user("晚安")
        val her = assistant("晚安呀")
        val session = session(him, her)

        val (plan, finished) = runTurn(session, FakeStreamProvider("[SILENT]"), forceNewNode = true, silent = true)

        assertEquals(listOf(him, her), finished.currentMessages)
        assertFalse("静默轮不能算发出去了", plan.sentIn(finished))
        assertNull("静默轮不能拿她的旧话当通知预览", plan.previewIn(finished))
    }

    @Test
    fun `normal turn reply is previewed and sent`() = runBlocking {
        val session = session(user("在吗"))

        val (plan, finished) = runTurn(session, FakeStreamProvider("在的"), forceNewNode = false)

        assertEquals("在的", finished.currentMessages.last().toText())
        assertTrue(plan.sentIn(finished))
        assertEquals("在的", plan.previewIn(finished))
    }

    // ---------------------------------------------------------------- W-008

    @Test
    fun `message he sends before her first token stays after her reply`() = runBlocking {
        val m1 = user("在吗")
        val m2 = user("问你个事")
        val session = session(m1, m2)
        val m3 = user("算了没事")

        val (_, finished) = runTurn(session, FakeStreamProvider("在的"), forceNewNode = false, beforeFirstChunk = {
            // 7 秒窗口到点、她还没吐出第一个字时他又发了一条（enqueueBatchedMessage 追加到末尾）
            session.update { it.copy(messageNodes = it.messageNodes + m3.toMessageNode()) }
        })

        assertEquals(listOf("在吗", "问你个事", "在的", "算了没事"), finished.currentMessages.map { it.toText() })
        // 下一轮（批次触发）回答 m3：历史以他的消息结尾，不会续写她上一条
        val next = finished.planReply(forceNewNode = false)
        assertNull(next.continuedMessageId)
        assertEquals(m3.id, next.history.last().id)
    }

    @Test
    fun `reply goes to the end when the message it answers was deleted meanwhile`() = runBlocking {
        val m1 = user("在吗")
        val m2 = user("问你个事")
        val session = session(m1, m2)

        val (_, finished) = runTurn(session, FakeStreamProvider("在的"), forceNewNode = false, beforeFirstChunk = {
            session.update { c -> c.copy(messageNodes = c.messageNodes.filterNot { it.currentMessage.id == m2.id }) }
        })

        assertEquals(listOf("在吗", "在的"), finished.currentMessages.map { it.toText() })
    }

    // ---------------------------------------------------------------- W-048

    @Test
    fun `silent turn after a tool call leaves no tool-only node behind`() = runBlocking {
        val him = user("今天好累")
        val her = assistant("抱抱")
        val session = session(him, her)
        var wrote = false
        val diary = Tool(name = "write_diary", description = "write_diary") {
            wrote = true
            listOf(UIMessagePart.Text("""{"ok":true}"""))
        }
        val provider = FakeStreamProvider("[SILENT]", toolFirst = "write_diary")

        val (plan, finished) = runTurn(session, provider, forceNewNode = true, silent = true, tools = listOf(diary))

        assertTrue("工具照常执行", wrote)
        assertEquals(2, provider.requests.size)
        assertEquals("静默轮不能留下只有工具调用的节点", listOf(him, her), finished.currentMessages)
        assertEquals(2, finished.messageNodes.size)
        assertFalse(plan.sentIn(finished))
        assertNull(plan.previewIn(finished))
    }

    @Test
    fun `tool call followed by a visible reply keeps the paired tool result in history`() = runBlocking {
        val him = user("今天好累")
        val session = session(him)
        val diary = Tool(name = "write_diary", description = "write_diary") {
            listOf(UIMessagePart.Text("""{"ok":true}"""))
        }

        val (plan, finished) = runTurn(
            session,
            FakeStreamProvider("记下来了", toolFirst = "write_diary"),
            forceNewNode = false,
            silent = true,
            tools = listOf(diary),
        )

        assertEquals(listOf("今天好累", "记下来了"), finished.currentMessages.map { it.toText().trim() })
        val tools = finished.currentMessages.flatMap { it.getTools() }
        assertEquals("工具调用跟着那句话留在历史里", listOf("write_diary"), tools.map { it.toolName })
        assertTrue("工具调用和结果成对", tools.all { it.isExecuted })
        assertTrue(plan.sentIn(finished))
    }

    // ---------------------------------------------------------------- W-058

    @Test
    fun `finishing a turn only touches the reply node`() = runBlocking {
        val history = (1..30).flatMap { listOf(user("他$it"), assistant("她$it")) }
        val session = session(*history.toTypedArray(), user("在吗"))
        val before = session.state.value.messageNodes

        val (_, finished) = runTurn(session, FakeStreamProvider("在的\n怎么啦"), forceNewNode = false)

        // 之前的节点原样复用（同一个实例），没有被整段拷贝一遍
        before.forEachIndexed { i, node -> assertTrue("节点 $i 被重建了", node === finished.messageNodes[i]) }
        assertEquals(listOf("在的", "怎么啦"), finished.currentMessages.drop(before.size).map { it.toText() })
    }

    @Test
    fun `turn without any output leaves the conversation alone`() = runBlocking {
        val session = session(user("在吗"), assistant(""))
        session.replyMessageId.value = null
        val before = session.state.value.messageNodes

        val finished = session.finishGeneration()

        assertTrue(before.zip(finished.messageNodes).all { (a, b) -> a === b })
        assertEquals(before.size, finished.messageNodes.size)
    }

    // ---------------------------------------------------------------- 夹具

    private suspend fun runTurn(
        session: ConversationSession,
        provider: FakeStreamProvider,
        forceNewNode: Boolean,
        silent: Boolean = false,
        tools: List<Tool> = emptyList(),
        beforeFirstChunk: suspend () -> Unit = {},
    ): Pair<ReplyPlan, Conversation> {
        session.replyMessageId.value = null
        val plan = session.state.value.planReply(forceNewNode = forceNewNode)
        session.replyMessageId.value = plan.continuedMessageId
        provider.beforeFirstChunk = beforeFirstChunk
        val request = AgentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            messages = emptyList(),
            tools = tools,
        )
        runAgent(plan.applyTo(request), hooks(silent)).collect { event ->
            if (event is AgentEvent.MessageUpdated) session.applyReply(plan, event.message)
        }
        return plan to session.finishGeneration()
    }

    private fun hooks(silent: Boolean) = object : AgentHooks {
        override suspend fun prepareStep(history: List<UIMessage>, reply: UIMessage?) =
            StepRequest(if (reply == null) history else history + reply, TextGenerationParams(model = model))

        // 模拟 WhaleOutputTransformer：[SILENT] 时把这一步的输出清空。和真实实现一样只看文字：
        // 这一步只有工具调用（没有文字）时原样保留
        override suspend fun finishStep(message: UIMessage, newPartsFrom: Int) =
            if (silent && message.parts.drop(newPartsFrom).filterIsInstance<UIMessagePart.Text>()
                    .any { it.text.trim() == "[SILENT]" }
            ) {
                message.copy(parts = message.parts.take(newPartsFrom))
            } else {
                message
            }
    }

    private suspend fun session(vararg messages: UIMessage): ConversationSession {
        val conversation = Conversation.ofId(
            conversationId,
            assistantId = assistantId,
            messages = messages.map { it.toMessageNode() },
        )
        val store = FakeConversationStore(conversation)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()), store, {})
        session.initialize(load = { store.persisted })
        return session
    }

    private fun user(text: String) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = yesterday)

    private fun assistant(text: String) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)), createdAt = yesterday)
}

/**
 * 回 [reply]；[toolFirst] 不为空时第一步先调用这个工具，第二步才回 [reply]。
 * [toolSteps] 是前几步依次调用的工具（第 k 步调用 `toolSteps[k-1]`，调用 id 是 `call-k`），都调完才回 [reply]。
 */
internal class FakeStreamProvider(
    private val reply: String,
    toolFirst: String? = null,
    private val toolSteps: List<String> = listOfNotNull(toolFirst),
) : Provider<ProviderSetting> {
    val requests = mutableListOf<List<UIMessage>>()
    var beforeFirstChunk: suspend () -> Unit = {}

    override suspend fun listModels(providerSetting: ProviderSetting): List<Model> = emptyList()

    override suspend fun generateText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): TextGenerationResult = error("stream only")

    override suspend fun streamText(
        providerSetting: ProviderSetting,
        messages: List<UIMessage>,
        params: TextGenerationParams,
    ): Flow<StreamChunk> {
        requests += messages
        val step = requests.size
        return flow {
            if (step == 1) beforeFirstChunk()
            val tool = toolSteps.getOrNull(step - 1)
            if (tool != null) {
                emit(StreamChunk.ToolCallStart("call-$step", tool))
                emit(StreamChunk.ToolCallDelta("call-$step", inputDelta = "{}"))
                emit(StreamChunk.ToolCallEnd("call-$step"))
            } else {
                text(reply)
            }
            emit(StreamChunk.Finish(finishReason = "stop"))
        }
    }

    private suspend fun FlowCollector<StreamChunk>.text(text: String) {
        emit(StreamChunk.TextStart("t"))
        emit(StreamChunk.TextDelta("t", text))
        emit(StreamChunk.TextEnd("t"))
    }
}
