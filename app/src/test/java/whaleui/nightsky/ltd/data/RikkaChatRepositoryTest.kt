package whaleui.nightsky.ltd.data

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.toMessageNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatEvent
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageIds
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.uuid.Uuid

/**
 * 发送流程（W-004 / W-025）：假的 [WhaleChatBackend] 手动推进会话和生成任务，
 * 看 [RikkaChatRepository.send] 发给界面的事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RikkaChatRepositoryTest {
    private val backend = FakeBackend()
    private val repository = RikkaChatRepository(
        backend,
        debugCommands = NoCommands,
        io = EmptyCoroutineContext,
        uploads = whaleui.nightsky.ltd.ui.chat.unusedUploads(),
    )

    @Test
    fun `her reply is published even when older messages are deleted during the send`() = runTest {
        val oldUser = backend.append(user("昨天的话"))
        val oldHer = backend.append(her("嗯"))
        val events = collectSend(10_001, "在吗").events

        backend.job.value = Job()
        runCurrent()
        // 撤销窗口过后，他删掉的两条旧消息这时才落库
        backend.remove(oldUser.id)
        backend.remove(oldHer.id)
        runCurrent()
        val reply = backend.append(her("在的"))
        runCurrent()
        backend.job.value = null
        advanceUntilIdle()

        assertEquals(listOf(MessageIds.forUuid(reply.id)), events.added())
        assertTrue(events.removed().isEmpty())
    }

    @Test
    fun `a published reply is not removed when an older message is deleted`() = runTest {
        backend.append(user("昨天的话"))
        val oldHer = backend.append(her("嗯"))
        val events = collectSend(10_001, "在吗").events

        backend.job.value = Job()
        runCurrent()
        val reply = backend.append(her("在的"))
        runCurrent()
        backend.remove(oldHer.id)
        runCurrent()
        backend.job.value = null
        advanceUntilIdle()

        assertEquals(listOf(MessageIds.forUuid(reply.id)), events.added())
        assertTrue(events.removed().isEmpty())
    }

    @Test
    fun `sending again mid-reply keeps updating her bubble and replaces it with its slices`() = runTest {
        backend.append(user("早"))
        val first = collectSend(10_001, "在吗")
        backend.job.value = Job()
        runCurrent()
        val merged = backend.append(her("早上好"))
        runCurrent()
        assertEquals(listOf(MessageIds.forUuid(merged.id)), first.events.added())

        // 她还在回，他又发一条：旧的发送流程被取消，新流程接手
        first.job.cancel()
        val second = collectSend(10_002, "醒了没")
        backend.update(merged.copy(parts = listOf(UIMessagePart.Text("消息1\n消息2"))))
        runCurrent()
        assertEquals(
            "新流程要继续更新她正在写的那个气泡，不能冻成半句",
            listOf(MessageIds.forUuid(merged.id)),
            second.events.updated(),
        )

        // 这一轮结束：合并节点被切成两片（换新 id），他第二条消息排在后面
        val slices = listOf(her("早上好"), her("你醒了吗"))
        backend.replace(merged.id, slices)
        backend.job.value = null
        runCurrent()
        // 批次立刻触发下一轮回答他第二条
        backend.job.value = Job()
        runCurrent()
        val next = backend.append(her("醒啦"))
        runCurrent()
        backend.job.value = null
        advanceUntilIdle()

        assertEquals(listOf(MessageIds.forUuid(merged.id)), second.events.removed())
        assertEquals(
            (slices + next).map { MessageIds.forUuid(it.id) },
            second.events.added(),
        )
        // 每个新气泡都带着它在库里的前一条：切片排在他第二条消息前面，下一轮的回复排在它后面
        val firstAsk = backend.stored("在吗")
        val secondAsk = backend.stored("醒了没")
        assertEquals(
            listOf(firstAsk.id, slices[0].id, secondAsk.id).map { MessageIds.forUuid(it) },
            second.events.filterIsInstance<ChatEvent.Added>().map { it.afterId },
        )
        assertEquals(
            listOf(firstAsk.id, secondAsk.id).map { MessageIds.forUuid(it) },
            (first.events + second.events).filterIsInstance<ChatEvent.IdAssigned>().map { it.messageId },
        )
    }

    @Test
    fun `his message is still handed over when the send flow is cancelled before it reaches ChatService`() = runTest {
        // 读会话卡住（真实环境里是复制附件 / 读库），这时他又按了一次发送，第一条的发送流程被取消
        backend.gate = kotlinx.coroutines.CompletableDeferred()
        val first = collectSend(10_001, "第一条")
        first.job.cancel()
        runCurrent()
        backend.gate!!.complete(Unit)
        // 发送流程在 backgroundScope 里，advanceUntilIdle 不等它，用 runCurrent 推进
        runCurrent()

        assertEquals(listOf("第一条"), backend.conversation.value.currentMessages.map { it.toText() })
    }

    /** W-042 / D5：语音文件不在了，气泡显示转语音之前的文字 */
    @Test
    fun `a voice whose file is gone is marked lost and keeps the words she said`() = runTest {
        val existing = java.io.File.createTempFile("voice", ".mp3").apply { writeBytes(byteArrayOf(1)); deleteOnExit() }
        backend.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Audio(
                        url = "file:///nowhere/gone.mp3",
                        metadata = kotlinx.serialization.json.buildJsonObject {
                            put("text", kotlinx.serialization.json.JsonPrimitive("晚安呀"))
                            put("durationMs", kotlinx.serialization.json.JsonPrimitive(2000))
                        },
                    ),
                ),
            ),
        )
        // 老消息：metadata 里没有 text，只能从工具入参里找（去掉语气标签）
        backend.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Tool(
                        toolCallId = "call-1",
                        toolName = "text_to_speech",
                        input = """{"text":"(laughs)你猜呀"}""",
                        output = listOf(
                            UIMessagePart.Text("""{"sent":true}"""),
                            UIMessagePart.Audio(url = "file:///nowhere/old.mp3"),
                        ),
                    ),
                ),
            ),
        )
        backend.append(
            UIMessage(
                role = MessageRole.ASSISTANT,
                parts = listOf(UIMessagePart.Audio(url = "file://" + existing.absolutePath)),
            ),
        )

        val voices = repository.loadInitial(1L)

        assertEquals(listOf(true, true, false), voices.map { it.voiceLost })
        assertEquals(listOf("晚安呀", "你猜呀", null), voices.map { it.voiceText })
    }

    /** 多图消息：库里一条消息、界面每张图一个气泡（乐观 id）。删其中一个气泡要落库（F3 遗留） */
    @Test
    fun `deleting one bubble of a multi image message removes that image from the stored message`() = runTest {
        val main = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(main)
        var vm: whaleui.nightsky.ltd.ui.chat.ChatViewModel? = null
        try {
            val model = whaleui.nightsky.ltd.ui.chat.chatViewModel(repository)
            vm = model
            model.addAttachments(
                listOf("file:///uploads/a.jpg", "file:///uploads/b.jpg").map { url ->
                    whaleui.nightsky.ltd.domain.chat.PendingAttachment(
                        id = url, uri = url, name = url.substringAfterLast('/'), mimeType = "image/jpeg", size = 1,
                    )
                },
            )
            model.onDraftChange("看看")
            model.send()
            runCurrent()
            val stored = backend.conversation.value.currentMessages.single()
            assertEquals(2, stored.parts.filterIsInstance<UIMessagePart.Image>().size)

            // 删第二张
            val second = model.uiState.value.messages.single { it.imageUris == listOf("file:///uploads/b.jpg") }
            model.delete(second)
            advanceTimeBy(6_000) // 撤销窗口 5.5s（U0：撤销 Bulletin 5s）
            runCurrent()

            val after = backend.conversation.value.currentMessages.single()
            assertEquals(stored.id, after.id)
            assertEquals(
                listOf("file:///uploads/a.jpg"),
                after.parts.filterIsInstance<UIMessagePart.Image>().map { it.url },
            )
            assertEquals(listOf("看看"), after.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })

            // 再删剩下那张和配文气泡：整条消息都没了
            assertEquals(listOf("file:///uploads/a.jpg"), model.uiState.value.messages[0].imageUris)
            assertEquals("看看", model.uiState.value.messages[1].text)
            model.uiState.value.messages.toList().forEach { model.delete(it) }
            advanceTimeBy(6_000) // 撤销窗口 5.5s（U0：撤销 Bulletin 5s）
            runCurrent()
            assertTrue(backend.conversation.value.currentMessages.isEmpty())
        } finally {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    @Test
    fun `a document he attaches is stored with its file url and real mime`() = runTest {
        val main = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(main)
        var vm: whaleui.nightsky.ltd.ui.chat.ChatViewModel? = null
        try {
            val file = java.io.File.createTempFile("letter", ".docx").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
            val url = "file://" + file.absolutePath
            val model = whaleui.nightsky.ltd.ui.chat.chatViewModel(repository)
            vm = model
            model.addAttachments(
                listOf(
                    whaleui.nightsky.ltd.domain.chat.PendingAttachment(
                        id = url,
                        uri = url,
                        name = "测试文档.docx",
                        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        size = 3,
                    ),
                ),
            )
            model.send()
            runCurrent()

            val document = backend.conversation.value.currentMessages.single()
                .parts.filterIsInstance<UIMessagePart.Document>().single()
            assertEquals(url, document.url)
            assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", document.mime)
            assertEquals("测试文档.docx", document.fileName)
        } finally {
            // 发送流程和清空输入框的延时都挂在 viewModelScope 上，换回 Main 之前先取消
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    // ---------------------------------------------------------------- R2b：重启后多图 / 图文混发的显示

    private fun storedUserMessage(vararg parts: UIMessagePart) =
        backend.append(UIMessage(role = MessageRole.USER, parts = parts.toList()))

    @Test
    fun `a stored multi image message with a caption loads as one bubble per image plus a text bubble`() = runTest {
        val stored = storedUserMessage(
            UIMessagePart.Text("看看这两张"),
            UIMessagePart.Image("file:///uploads/a.jpg"),
            UIMessagePart.Image("file:///uploads/b.jpg"),
        )

        val bubbles = repository.loadInitial(1L)

        assertEquals(3, bubbles.size)
        assertEquals(listOf(listOf("file:///uploads/a.jpg"), listOf("file:///uploads/b.jpg"), emptyList()), bubbles.map { it.imageUris })
        assertEquals(listOf(null, null, "看看这两张"), bubbles.map { it.text })
        assertEquals("最后一个气泡用整条消息的 id", MessageIds.forUuid(stored.id), bubbles.last().id)
        assertEquals(3, bubbles.map { it.id }.toSet().size)
        assertTrue(bubbles.all { it.partOf == MessageIds.forUuid(stored.id) && !it.fromHer })
    }

    @Test
    fun `image file and caption sent together load as separate bubbles in send order`() = runTest {
        val document = UIMessagePart.Document(
            url = "file:///uploads/letter.pdf",
            fileName = "信.pdf",
            mime = "application/pdf",
            metadata = kotlinx.serialization.json.buildJsonObject { put("size", kotlinx.serialization.json.JsonPrimitive(1234)) },
        )
        storedUserMessage(UIMessagePart.Text("附件"), UIMessagePart.Image("file:///uploads/a.jpg"), document)

        val bubbles = repository.loadInitial(1L)

        assertEquals(3, bubbles.size)
        assertEquals(listOf("file:///uploads/a.jpg"), bubbles[0].imageUris)
        assertEquals(null, bubbles[0].documentName)
        assertEquals("信.pdf", bubbles[1].documentName)
        assertEquals(1234L, bubbles[1].documentSize)
        assertEquals("application/pdf", bubbles[1].documentMime)
        assertTrue(bubbles[1].imageUris.isEmpty())
        assertEquals("附件", bubbles[2].text)
    }

    @Test
    fun `a single image or plain text stays one bubble with no part link`() = runTest {
        val image = storedUserMessage(UIMessagePart.Image("file:///uploads/a.jpg"))
        val text = storedUserMessage(UIMessagePart.Text("只有字"))

        val bubbles = repository.loadInitial(1L)

        assertEquals(listOf(MessageIds.forUuid(image.id), MessageIds.forUuid(text.id)), bubbles.map { it.id })
        assertTrue(bubbles.all { it.partOf == null })
    }

    /** 重启后（新的 ViewModel 从库里读）删单张图 / 删配文都只删那一部分，删完最后一个整条消息才没 */
    @Test
    fun `after a restart each bubble of a stored multi image message can be deleted on its own`() = runTest {
        val main = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(main)
        var vm: whaleui.nightsky.ltd.ui.chat.ChatViewModel? = null
        try {
            val stored = storedUserMessage(
                UIMessagePart.Text("看看"),
                UIMessagePart.Image("file:///uploads/a.jpg"),
                UIMessagePart.Image("file:///uploads/b.jpg"),
            )
            val model = whaleui.nightsky.ltd.ui.chat.chatViewModel(repository)
            vm = model
            runCurrent()
            assertEquals(3, model.uiState.value.messages.size)

            model.delete(model.uiState.value.messages.single { it.imageUris == listOf("file:///uploads/a.jpg") })
            advanceTimeBy(6_000) // 撤销窗口 5.5s（U0：撤销 Bulletin 5s）
            runCurrent()
            val afterImage = backend.conversation.value.currentMessages.single()
            assertEquals(stored.id, afterImage.id)
            assertEquals(listOf("file:///uploads/b.jpg"), afterImage.parts.filterIsInstance<UIMessagePart.Image>().map { it.url })
            assertEquals(listOf("看看"), afterImage.parts.filterIsInstance<UIMessagePart.Text>().map { it.text })

            // 删配文（这个气泡的 id 就是整条消息的 id，也不能整条删）
            model.delete(model.uiState.value.messages.single { it.text == "看看" })
            advanceTimeBy(6_000) // 撤销窗口 5.5s（U0：撤销 Bulletin 5s）
            runCurrent()
            val afterText = backend.conversation.value.currentMessages.single()
            assertEquals(listOf(UIMessagePart.Image("file:///uploads/b.jpg")), afterText.parts)

            model.delete(model.uiState.value.messages.single())
            advanceTimeBy(6_000) // 撤销窗口 5.5s（U0：撤销 Bulletin 5s）
            runCurrent()
            assertTrue(backend.conversation.value.currentMessages.isEmpty())
        } finally {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    /** 发送时界面上的样子和重启后读出来的一样；交给模型的部件顺序仍是文字在前 */
    @Test
    fun `bubbles shown when sending match the bubbles loaded after a restart`() = runTest {
        val main = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)
        kotlinx.coroutines.Dispatchers.setMain(main)
        var vm: whaleui.nightsky.ltd.ui.chat.ChatViewModel? = null
        try {
            val model = whaleui.nightsky.ltd.ui.chat.chatViewModel(repository)
            vm = model
            model.addAttachments(
                listOf("file:///uploads/a.jpg", "file:///uploads/b.jpg").map { url ->
                    whaleui.nightsky.ltd.domain.chat.PendingAttachment(
                        id = url, uri = url, name = url.substringAfterLast('/'), mimeType = "image/jpeg", size = 1,
                    )
                },
            )
            model.onDraftChange("配文")
            model.send()
            runCurrent()

            val stored = backend.conversation.value.currentMessages.single()
            assertTrue("文字部件在前", stored.parts.first() is UIMessagePart.Text)

            fun List<ChatMessage>.shape() = map { Triple(it.imageUris, it.text, it.documentName) }
            val shown = model.uiState.value.messages.shape()
            assertEquals(
                listOf(
                    Triple(listOf("file:///uploads/a.jpg"), null, null),
                    Triple(listOf("file:///uploads/b.jpg"), null, null),
                    Triple(emptyList(), "配文", null),
                ),
                shown,
            )
            assertEquals(shown, repository.loadInitial(1L).shape())
        } finally {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
            kotlinx.coroutines.Dispatchers.resetMain()
        }
    }

    // ---------------------------------------------------------------- R2：调试命令只在 debug 包里

    @Test
    fun `without debug commands a slash-fake message is sent to her like any other`() = runTest {
        val sent = collectSend(localId = 77L, text = "/cmd 消息1")

        assertEquals(listOf("/cmd 消息1"), backend.conversation.value.currentMessages.map { it.toText() })
        assertTrue(sent.events.any { it is ChatEvent.IdAssigned })
        sent.job.cancel()
    }

    // ---------------------------------------------------------------- 夹具

    /** release 里的样子：没有任何调试命令 */
    private object NoCommands : DebugChatCommands {
        override fun isCommand(rawText: String) = false
        override suspend fun reply(rawText: String): DebugTurn? = null
    }

    private class Collected(val events: MutableList<ChatEvent>, val job: Job)

    private fun TestScope.collectSend(localId: Long, text: String): Collected {
        val events = mutableListOf<ChatEvent>()
        val outgoing = ChatMessage(id = localId, fromHer = false, text = text, time = "08:00", status = MessageStatus.Sending)
        val job = backgroundScope.launch { repository.send(1L, listOf(outgoing)).collect { events += it } }
        runCurrent()
        return Collected(events, job)
    }

    private fun List<ChatEvent>.added() = filterIsInstance<ChatEvent.Added>().map { it.message.id }
    private fun List<ChatEvent>.updated() = filterIsInstance<ChatEvent.Updated>().map { it.message.id }.distinct()
    private fun List<ChatEvent>.removed() = filterIsInstance<ChatEvent.Removed>().flatMap { it.messageIds }

    private fun user(text: String) = UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)))
    private fun her(text: String) = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text(text)))
}

private class FakeBackend : WhaleChatBackend {
    private val id = Uuid.random()
    val conversation = MutableStateFlow(Conversation.ofId(id))
    val job = MutableStateFlow<Job?>(null)

    /** 不为 null 时 getConversation 要等它完成（模拟发送前的慢操作） */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    fun append(message: UIMessage): UIMessage {
        conversation.update { it.copy(messageNodes = it.messageNodes + message.toMessageNode()) }
        return message
    }

    fun remove(messageId: Uuid) = conversation.update { c ->
        c.copy(messageNodes = c.messageNodes.filterNot { node -> node.messages.any { it.id == messageId } })
    }

    fun update(message: UIMessage) = conversation.update { c ->
        c.copy(messageNodes = c.messageNodes.map { node ->
            if (node.messages.any { it.id == message.id }) node.copy(messages = listOf(message)) else node
        })
    }

    fun replace(messageId: Uuid, with: List<UIMessage>) = conversation.update { c ->
        c.copy(messageNodes = c.messageNodes.flatMap { node ->
            if (node.messages.any { it.id == messageId }) with.map { it.toMessageNode() } else listOf(node)
        })
    }

    fun stored(text: String) = conversation.value.currentMessages.single { it.toText() == text }

    override suspend fun conversationId(): Uuid = id
    override suspend fun getConversation(conversationId: Uuid): Conversation {
        gate?.await()
        return conversation.value
    }
    override fun conversationFlow(conversationId: Uuid): Flow<Conversation> = conversation
    override fun generationJobFlow(conversationId: Uuid): Flow<Job?> = job

    override fun sendMessage(conversationId: Uuid, parts: List<UIMessagePart>): Uuid =
        append(UIMessage(role = MessageRole.USER, parts = parts)).id

    override suspend fun deleteMessageEverywhere(conversationId: Uuid, messageId: Uuid) = remove(messageId)

    override suspend fun replaceMessageParts(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) =
        conversation.update { c ->
            c.copy(messageNodes = c.messageNodes.map { node ->
                node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(parts = parts) else it })
            })
        }

    override suspend fun appendDebugTurn(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        assistantParts: List<List<UIMessagePart>>,
    ) = error("not used")
}
