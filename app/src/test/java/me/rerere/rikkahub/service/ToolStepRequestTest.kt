package me.rerere.rikkahub.service

import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDateTime
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.ai.ChatStepHooks
import me.rerere.rikkahub.data.ai.ChatTurnContext
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.ai.WhaleCompatTransformer
import whaleui.nightsky.ltd.ai.WhaleGuardTransformer
import whaleui.nightsky.ltd.ai.WhaleHistoryLimiter
import whaleui.nightsky.ltd.ai.WhaleOutputTransformer
import whaleui.nightsky.ltd.ai.fakeContext
import whaleui.nightsky.ltd.prompt.WhaleTurnClock
import whaleui.nightsky.ltd.schedule.SeedTaskIds
import whaleui.nightsky.ltd.sticker.InMemoryStickerRepository
import whaleui.nightsky.ltd.sticker.Sticker
import whaleui.nightsky.ltd.sticker.StickerAvailability
import whaleui.nightsky.ltd.sticker.StickerPack
import whaleui.nightsky.ltd.whale.WhaleAlarmTurn
import whaleui.nightsky.ltd.whale.WhaleGate
import kotlin.uuid.Uuid

/**
 * W-026：一轮里调用了工具时，每一步（第 1…n 步）发给模型的请求结构必须一致：
 * 系统提示词 → 历史 → 本轮触发的消息（普通轮：他最后的 user 消息带每轮注入；闹钟轮：合成的闹钟消息）
 * → 本轮的助手消息（工具调用和结果）。
 *
 * 用真实的 AgentLoop、会话、[ChatStepHooks] 和输入转换链（截历史、每轮注入、原生报时 / 提示词注入、
 * 文档、DeepSeek 兼容 + 连发合并）跑整轮，只有模型是假的。没有放进来的只有占位符、OCR、模板三个转换：
 * 它们要 Koin / Pebble，而且只是逐条改文字，和消息顺序无关。
 */
class ToolStepRequestTest {
    private val conversationId = Uuid.random()
    private val assistantId = Uuid.random()
    private val model = Model(modelId = "fake")
    private val zone = ZoneId.systemDefault()
    private val start = ZonedDateTime.of(2026, 10, 26, 9, 0, 0, 0, zone)
    private val readPrompt: (String) -> String = { name -> File("src/main/assets/whale/prompts/$name").readText() }

    // 每读一次就过去一分钟：工具执行慢的时候第 2 步可能已经跨了一分钟，每轮注入里的报时也不能变
    private var ticks = 0L
    private val clock = WhaleTurnClock { start.plusMinutes(ticks++) }

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

    private val goodnight = user("晚安", LocalDateTime(2026, 10, 25, 23, 0))
    private val herGoodnight = assistant("晚安呀", LocalDateTime(2026, 10, 25, 23, 1))

    // ---------------------------------------------------------------- 普通轮

    @Test
    fun `normal turn keeps the injection on his message at every step`() = runBlocking {
        val morning = user("早", LocalDateTime(2026, 10, 26, 8, 58))

        val requests = runTurn(listOf(goodnight, herGoodnight, morning), toolSteps = listOf("write_diary"))

        val trigger = "user[now,reminder,voice,sticker,antirepeat]:早"
        assertSteps(
            requests,
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", trigger),
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", trigger, "assistant:{write_diary#call-1=ok}"),
        )
    }

    @Test
    fun `two tool calls keep the same request shape for three steps`() = runBlocking {
        val morning = user("早", LocalDateTime(2026, 10, 26, 8, 58))

        val requests = runTurn(
            listOf(goodnight, herGoodnight, morning),
            toolSteps = listOf("write_diary", "update_memory"),
        )

        val head = listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", "user[now,reminder,voice,sticker,antirepeat]:早")
        assertSteps(
            requests,
            head,
            head + "assistant:{write_diary#call-1=ok}",
            head + "assistant:{write_diary#call-1=ok}{update_memory#call-2=ok}",
        )
    }

    @Test
    fun `batched messages are merged and keep the injection at every step`() = runBlocking {
        val first = user("早", LocalDateTime(2026, 10, 26, 8, 57))
        val second = user("在吗", LocalDateTime(2026, 10, 26, 8, 58))

        val requests = runTurn(listOf(goodnight, herGoodnight, first, second), toolSteps = listOf("write_diary"))

        val trigger = "user[now,reminder,voice,sticker,antirepeat]:早/在吗"
        assertSteps(
            requests,
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", trigger),
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", trigger, "assistant:{write_diary#call-1=ok}"),
        )
    }

    // ---------------------------------------------------------------- 闹钟轮

    @Test
    fun `alarm turn keeps her last message apart and the alarm note before the tool call`() = runBlocking {
        val requests = runTurn(
            listOf(goodnight, herGoodnight),
            toolSteps = listOf("write_diary"),
            alarm = WhaleAlarmTurn("（闹钟：早上了）", "task-1"),
        )

        val alarm = "user[now,cron,alarmnote,voice]:"
        assertSteps(
            requests,
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", alarm),
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", alarm, "assistant:{write_diary#call-1=ok}"),
        )
    }

    @Test
    fun `night alarm turn with memory tidying keeps the same shape`() = runBlocking {
        val requests = runTurn(
            listOf(goodnight, herGoodnight),
            toolSteps = listOf("update_memory"),
            alarm = WhaleAlarmTurn("（闹钟：该睡了）", SeedTaskIds.NIGHT),
        )

        val alarm = "user[now,cron,alarmnote,tidy,voice]:"
        assertSteps(
            requests,
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", alarm),
            listOf("system:SYSTEM", "user:晚安", "assistant:晚安呀", alarm, "assistant:{update_memory#call-1=ok}"),
        )
    }

    // ---------------------------------------------------------------- 断言

    /**
     * 整条消息序列逐步比对；再确认第 k 步的前面部分和第 1 步逐字一样（角色和全部部件），
     * 本轮的助手消息只出现在最后。
     */
    private fun assertSteps(requests: List<List<UIMessage>>, vararg expected: List<String>) {
        assertEquals(
            "每一步的请求消息序列",
            expected.toList(),
            requests.map { step -> step.map { it.describe() } },
        )
        val first = requests.first().map { it.role to it.parts }
        requests.drop(1).forEachIndexed { index, step ->
            assertEquals(
                "第 ${index + 2} 步的前面部分要和第 1 步逐字一样",
                first,
                step.take(first.size).map { it.role to it.parts },
            )
            assertEquals(MessageRole.ASSISTANT, step.last().role)
            assertTrue(step.last().getTools().all { it.isExecuted })
        }
    }

    /** `角色[按出现顺序的注入标记]:正文首段{工具名#调用 id=结果}`；正文去掉注入部分，换行写成 / */
    private fun UIMessage.describe(): String {
        val text = parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        val marks = MARKERS.mapNotNull { (label, needle) -> text.indexOf(needle).takeIf { it >= 0 }?.let { it to label } }
            .sortedBy { it.first }
            .map { it.second }
        val body = text.substringBefore("[现在 ").trim().replace("\n", "/")
        val tools = getTools().joinToString("") { tool ->
            val output = tool.output.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }
            "{${tool.toolName}#${tool.toolCallId}=$output}"
        }
        return buildString {
            append(role.name.lowercase())
            if (marks.isNotEmpty()) append(marks.joinToString(",", "[", "]"))
            append(':')
            append(body)
            append(tools)
        }
    }

    // ---------------------------------------------------------------- 夹具

    private suspend fun runTurn(
        history: List<UIMessage>,
        toolSteps: List<String>,
        alarm: WhaleAlarmTurn? = null,
    ): List<List<UIMessage>> {
        val session = session(history)
        val provider = FakeStreamProvider("好", toolSteps = toolSteps)
        val tools = listOf("write_diary", "update_memory").map { name ->
            Tool(name = name, description = name) { listOf(UIMessagePart.Text("ok")) }
        }
        val turn = ChatTurnContext(
            settings = Settings(),
            assistant = Assistant(id = assistantId),
            model = model,
            providerSetting = ProviderSetting.OpenAI(),
            conversationId = conversationId,
            conversationSystemPrompt = null,
            workspaceCwd = null,
            memories = emptyList(),
            tools = tools,
            whaleAlarm = alarm,
        )
        val guard = WhaleGuardTransformer(readPrompt = readPrompt, clock = clock, stickerAvailability = stickers())
        val perMessage = listOf(DocumentAsPromptTransformer, WhaleCompatTransformer())
        val hooks = ChatStepHooks(
            context = fakeContext(),
            turn = turn,
            historyTransformers = ChatRequestFactory.historyChain(WhaleHistoryLimiter(), guard, perMessage),
            replyTransformers = perMessage,
            outputTransformers = listOf(WhaleOutputTransformer()),
            systemPrompt = { "SYSTEM" },
            clock = clock,
        )

        session.replyMessageId.value = null
        val plan = session.state.value.planReply(forceNewNode = alarm != null)
        session.replyMessageId.value = plan.continuedMessageId
        val request = AgentRequest(
            provider = provider,
            providerSetting = ProviderSetting.OpenAI(),
            messages = emptyList(),
            tools = tools,
        )
        runAgent(plan.applyTo(request), hooks).collect { event ->
            if (event is AgentEvent.MessageUpdated) session.applyReply(plan, event.message)
        }
        session.finishGeneration()
        assertEquals("工具调完还要再请求一次", toolSteps.size + 1, provider.requests.size)
        return provider.requests
    }

    private suspend fun session(messages: List<UIMessage>): ConversationSession {
        val conversation = Conversation.ofId(
            conversationId,
            assistantId = assistantId,
            messages = messages.map { it.toMessageNode() },
        )
        val store = FakeConversationStore(conversation)
        val session = ConversationSession(conversationId, Conversation.ofId(conversationId), CoroutineScope(SupervisorJob()), store, {})
        session.initialize(load = { store.persisted })
        return session
    }

    private suspend fun stickers(): StickerAvailability {
        val repository = InMemoryStickerRepository()
        repository.savePack(
            StickerPack(
                id = "01",
                name = "冬季约会",
                titleOriginal = "冬季约会",
                characters = emptyList(),
                animated = true,
                hasSoundOriginally = false,
                source = "测试",
                licenseNote = "仅个人使用",
                sortOrder = 0,
            ),
            listOf(
                Sticker(
                    id = "01-0",
                    packId = "01",
                    filePath = "stickers/01/0.webp",
                    thumbPath = "stickers/_thumbs/01-0.png",
                    description = "测试",
                    detail = "测试的详细说明",
                    emotions = listOf("开心"),
                ),
            ),
        )
        return StickerAvailability(repository, ttlMs = 0L)
    }

    private fun user(text: String, at: LocalDateTime) =
        UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)), createdAt = at)

    private fun assistant(text: String, at: LocalDateTime) =
        UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)), createdAt = at)

    private companion object {
        /** 每轮注入的固定开头（和 WhaleRequestLog 的标记一致，另加表情提醒和睡前整理） */
        val MARKERS = listOf(
            "now" to "[现在 ",
            "reminder" to "（这段是你心里的小提醒",
            "voice" to "（这条也可以发成语音",
            "sticker" to "（这条也可以配张表情包",
            "antirepeat" to "（你最近几次是这样开头的",
            "cron" to "（这次是你自己的闹钟响了",
            "alarmnote" to "（闹钟：",
            "tidy" to "（睡前顺手理一理",
        )
    }
}
