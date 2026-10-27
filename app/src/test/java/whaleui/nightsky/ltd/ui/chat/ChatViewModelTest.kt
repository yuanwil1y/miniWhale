package whaleui.nightsky.ltd.ui.chat

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatEvent
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.domain.chat.MessageIds
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import whaleui.nightsky.ltd.domain.chat.PendingAttachment

/**
 * ChatViewModel 的消息身份与补显（W-003）。仓库是脚本化的假实现：
 * [ScriptedChatRepository.db] 就是「数据库里」的消息，send 时按脚本发事件。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `history loaded from the database is shown at once and never replayed`() = runTest(dispatcher) {
        // W-062：走真实的加载路径（openConversation → loadInitial），不是只看一个新建的空队列
        val repo = ScriptedChatRepository()
        repeat(5) { index -> repo.storeHer("历史第 $index 条，字数多一点，走节奏的话每条都要等一会儿") }
        val vm = chatViewModel(repo)

        // 只跑已经排队的任务、不推进虚拟时间：要是历史进了待发队列，第 2 条起都要等节奏，这里就看不全
        runCurrent()
        assertEquals(repo.db.map { it.id }, vm.uiState.value.messages.map { it.id })
        assertEquals(false, vm.uiState.value.isHerTyping)

        // 生成结束时的补显（带动画）也不能把历史当成新消息再放一遍
        vm.syncNewMessages(animated = true)
        runCurrent()
        assertEquals(false, vm.uiState.value.isHerTyping)
        advanceUntilIdle()
        assertEquals(repo.db.map { it.id }, vm.uiState.value.messages.map { it.id })
    }

    @Test
    fun `his message is not shown again when her turn ends`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        repo.onSend = { outgoing ->
            flow {
                val stored = repo.storeUser(outgoing.single())
                emit(ChatEvent.StatusChanged(outgoing.single().id, MessageStatus.Sent))
                repo.assignedIds?.invoke(this, outgoing.map { it.id }, stored.id)
                emit(ChatEvent.Typing(true))
                emit(ChatEvent.Added(repo.storeHer("在的")))
                emit(ChatEvent.Typing(false))
            }
        }

        vm.onDraftChange("在吗")
        vm.send()
        advanceUntilIdle()
        // 每轮结束 WhaleRoot 都会调一次（ChatGenerationEnded）
        vm.syncNewMessages(animated = true)
        advanceUntilIdle()

        val texts = vm.uiState.value.messages.map { it.text }
        assertEquals(listOf("在吗", "在的"), texts)
    }

    @Test
    fun `optimistic message takes the stored id so later sync and delete find it`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        repo.assignedIds = { local, stored -> emit(ChatEvent.IdAssigned(local, stored)) }
        repo.onSend = { outgoing ->
            flow {
                val stored = repo.storeUser(outgoing.single())
                emit(ChatEvent.StatusChanged(outgoing.single().id, MessageStatus.Sent))
                repo.assignedIds?.invoke(this, outgoing.map { it.id }, stored.id)
            }
        }

        vm.onDraftChange("在吗")
        vm.send()
        advanceUntilIdle()

        val mine = vm.uiState.value.messages.single()
        assertEquals(repo.db.single().id, mine.id)
        assertEquals(MessageStatus.Sent, mine.status)

        vm.syncNewMessages(animated = false)
        advanceUntilIdle()
        assertEquals(1, vm.uiState.value.messages.size)

        vm.delete(mine)
        advanceUntilIdle()
        assertEquals(listOf(mine.id), repo.deleted)
    }

    @Test
    fun `her slices land before the message he sent while she was replying`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        lateinit var firstStored: ChatMessage
        repo.onSend = { outgoing ->
            flow {
                firstStored = repo.storeUser(outgoing.single())
                emit(ChatEvent.IdAssigned(outgoing.map { it.id }, firstStored.id))
            }
        }
        vm.onDraftChange("在吗")
        vm.send()
        advanceUntilIdle()

        // 她还在写的时候他又发了一条：库里她的切片排在这条前面
        repo.onSend = { outgoing ->
            flow {
                val second = repo.storeUser(outgoing.single())
                emit(ChatEvent.IdAssigned(outgoing.map { it.id }, second.id))
                val s1 = repo.storeHer("早上好")
                val s2 = repo.storeHer("你醒了吗")
                emit(ChatEvent.Added(s1, afterId = firstStored.id))
                emit(ChatEvent.Added(s2, afterId = s1.id))
                emit(ChatEvent.Typing(false))
            }
        }
        vm.onDraftChange("醒了没")
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf("在吗", "早上好", "你醒了吗", "醒了没"), vm.uiState.value.messages.map { it.text })
    }

    @Test
    fun `synced messages are placed where they are in the database`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val early = repo.storeHer("早")
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        repo.onSend = { outgoing ->
            flow {
                val stored = repo.storeUser(outgoing.single())
                emit(ChatEvent.IdAssigned(outgoing.map { it.id }, stored.id))
            }
        }
        vm.onDraftChange("在吗")
        vm.send()
        advanceUntilIdle()
        // 闹钟轮的回复在库里排在他这条之前（她先开口，他的消息后到）
        repo.db.add(1, ChatMessage(id = -100, fromHer = true, text = "起床啦", time = "08:00"))

        vm.syncNewMessages(animated = true)
        advanceUntilIdle()

        assertEquals(listOf(early.text, "起床啦", "在吗"), vm.uiState.value.messages.map { it.text })
    }

    @Test
    fun `pressing send twice quickly does not lose the first message`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        repo.onSend = { outgoing ->
            flow {
                // 交给 ChatService 之前还有一段要等（复制附件、读库）
                kotlinx.coroutines.delay(100)
                val stored = repo.storeUser(outgoing.single())
                emit(ChatEvent.IdAssigned(outgoing.map { it.id }, stored.id))
                emit(ChatEvent.StatusChanged(stored.id, MessageStatus.Sent))
                // 然后一直等她回
                kotlinx.coroutines.awaitCancellation()
            }
        }

        vm.onDraftChange("第一条")
        vm.send()
        vm.onDraftChange("第二条")
        vm.send()
        advanceTimeBy(1_000)

        assertEquals(listOf("第一条", "第二条"), repo.db.map { it.text })
        val shown = vm.uiState.value.messages
        assertEquals(repo.db.map { it.id }, shown.map { it.id })
        assertEquals(listOf(MessageStatus.Sent, MessageStatus.Sent), shown.map { it.status })
        vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
    }

    @Test
    fun `picked files are copied before they become attachments and rejections are explained`() = runTest(dispatcher) {
        val dir = java.nio.file.Files.createTempDirectory("whale-vm-uploads").toFile()
        val source = object : whaleui.nightsky.ltd.data.uploads.UploadSource {
            override fun describe(uri: String) = when (uri) {
                "content://ok" -> whaleui.nightsky.ltd.data.uploads.UploadInfo("清单.txt", 6, "text/plain")
                else -> whaleui.nightsky.ltd.data.uploads.UploadInfo("大.pdf", 50L * 1024 * 1024, "application/pdf")
            }
            override fun open(uri: String) = java.io.ByteArrayInputStream("牛奶".toByteArray())
        }
        val uploads = whaleui.nightsky.ltd.data.uploads.ChatUploads(dir, source, { _, _, _ -> null })
        val vm = ChatViewModel(Application(), ScriptedChatRepository(), whaleui.nightsky.ltd.sticker.InMemoryStickerRepository(), uploads = uploads, io = dispatcher)
        advanceUntilIdle()

        vm.attach(listOf("content://ok", "content://big"))
        advanceUntilIdle()

        val attached = vm.uiState.value.pendingAttachments.single()
        assertEquals("清单.txt", attached.name)
        assertEquals("text/plain", attached.mimeType)
        assertEquals(true, attached.uri.startsWith("file://"))
        assertEquals(true, vm.uiState.value.notice?.contains("大.pdf"))
        vm.consumeNotice()
        assertEquals(null, vm.uiState.value.notice)
    }

    /** W-042 / D5：语音文件读不出来时不播内置示例音频（别人的声音） */
    @Test
    fun `a voice message whose file is gone does not play anything`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        repo.db += ChatMessage(
            id = -1, fromHer = true, voiceSeconds = 3, voiceUrl = "file:///nowhere/gone.mp3", time = "08:00",
        )
        val vm = chatViewModel(repo)
        advanceUntilIdle()

        vm.toggleVoice(vm.uiState.value.messages.single())
        advanceUntilIdle()

        assertEquals(null, vm.uiState.value.voice.messageId)
        // 气泡改成「语音已丢失」+ 原文
        assertEquals(true, vm.uiState.value.messages.single().voiceLost)
    }

    @Test
    fun `several bubbles sent at once keep their own ids`() = runTest(dispatcher) {
        val repo = ScriptedChatRepository()
        val vm = chatViewModel(repo)
        advanceUntilIdle()
        repo.onSend = { outgoing ->
            flow {
                val stored = repo.storeUser(outgoing.first())
                emit(ChatEvent.IdAssigned(outgoing.map { it.id }, stored.id))
                emit(ChatEvent.Added(repo.storeHer("好看")))
            }
        }

        vm.addAttachments(
            listOf(
                PendingAttachment(id = "a", uri = "file:///a.png", name = "a.png", mimeType = "image/png", size = 1),
                PendingAttachment(id = "b", uri = "file:///b.png", name = "b.png", mimeType = "image/png", size = 1),
            ),
        )
        vm.send()
        advanceUntilIdle()
        vm.syncNewMessages(animated = false)
        advanceUntilIdle()

        val messages = vm.uiState.value.messages
        assertEquals(listOf(false, false, true), messages.map { it.fromHer })
        assertEquals(messages.size, messages.map { it.id }.toSet().size)
    }
}

/** 脚本化的假仓库：[db] 按顺序保存「库里」的消息，id 用负数（和 MessageIds 一样） */
internal class ScriptedChatRepository : ChatRepository {
    val db = mutableListOf<ChatMessage>()
    private var nextId = -1L
    var onSend: (List<ChatMessage>) -> Flow<ChatEvent> = { flow { } }

    /** 仓库发「乐观 id → 库 id」对应时用；为 null 表示不发 */
    var assignedIds: (suspend FlowCollector<ChatEvent>.(List<Long>, Long) -> Unit)? = null

    fun storeUser(message: ChatMessage): ChatMessage =
        message.copy(id = nextId--, status = MessageStatus.Read).also { db += it }

    fun storeHer(text: String): ChatMessage =
        ChatMessage(id = nextId--, fromHer = true, text = text, time = "08:00").also { db += it }

    override suspend fun loadInitial(conversationId: Long): List<ChatMessage> = db.toList()

    override suspend fun loadOlder(conversationId: Long): List<ChatMessage> = emptyList()

    override fun send(conversationId: Long, outgoing: List<ChatMessage>): Flow<ChatEvent> = onSend(outgoing)

    val deleted = mutableListOf<Long>()

    override suspend fun deleteMessage(conversationId: Long, messageId: Long) {
        deleted += messageId
    }
}
