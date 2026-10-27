package me.rerere.rikkahub.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionTest {
    @Test
    fun `metadata edits survive reopening and final generation save`() = runBlocking {
        val id = Uuid.random()
        val oldAssistant = Uuid.random()
        val targetAssistant = Uuid.random()
        val store =
            FakeConversationStore(Conversation.ofId(id, assistantId = oldAssistant).copy(folderId = Uuid.random()))
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })
        val streaming = session.update { it.updateCurrentMessages(listOf(partialReply())) }

        session.updateMetadata(
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { store.persisted = store.persisted!!.copy(isPinned = it.isPinned) },
        )
        session.updateMetadata(
            transform = { it.copy(assistantId = targetAssistant, folderId = null) },
            persist = { store.persisted = store.persisted!!.copy(assistantId = it.assistantId, folderId = null) },
        )

        session.initialize(load = { error("Returning to an active conversation must not reload its messages") })
        assertTrue(session.state.value.isPinned)
        assertEquals(targetAssistant, session.state.value.assistantId)
        assertNull(session.state.value.folderId)
        assertEquals(streaming.messageNodes, session.state.value.messageNodes)
        assertTrue(store.persisted!!.messageNodes.isEmpty())

        session.finishGeneration()
        val persisted = store.persisted!!
        assertTrue(persisted.isPinned)
        assertEquals(targetAssistant, persisted.assistantId)
        assertNull(persisted.folderId)
        assertEquals(streaming.currentMessages.single().id, persisted.currentMessages.single().id)
    }

    @Test
    fun `metadata edit loads history when the conversation has no initialized session`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id, assistantId = Uuid.random())
            .updateCurrentMessages(listOf(UIMessage.user("existing history")))
        val session = ConversationSession(id, Conversation.ofId(id), this, FakeConversationStore(), {})
        session.initialize(load = { persisted })
        session.updateMetadata(
            transform = { it.copy(isPinned = !it.isPinned) },
            persist = { persisted = persisted.copy(isPinned = it.isPinned) },
        )
        session.initialize(load = { error("Must keep the initialized session") })
        assertEquals(persisted, session.state.value)
        assertEquals("existing history", session.state.value.currentMessages.single().toText())
    }

    @Test
    fun `concurrent pin toggles persist in order without replacing streamed messages`() = runBlocking {
        val id = Uuid.random()
        var persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, FakeConversationStore(), {})
        session.initialize(load = { persisted })
        val allowFirstSave = CompletableDeferred<Unit>()
        val writtenPins = mutableListOf<Boolean>()
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                transform = { it.copy(isPinned = !it.isPinned) },
                persist = {
                    allowFirstSave.await()
                    persisted = persisted.copy(isPinned = it.isPinned)
                    writtenPins.add(it.isPinned)
                },
            )
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            session.updateMetadata(
                transform = { it.copy(isPinned = !it.isPinned) },
                persist = {
                    persisted = persisted.copy(isPinned = it.isPinned)
                    writtenPins.add(it.isPinned)
                },
            )
        }
        // 第二次修改要等第一次落库完成，内存和数据库的先后始终一致
        assertTrue(session.state.value.isPinned)
        assertFalse(second.isCompleted)
        val streaming = session.update { it.updateCurrentMessages(listOf(partialReply())) }
        allowFirstSave.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf(true, false), writtenPins)
        assertFalse(persisted.isPinned)
        assertFalse(session.state.value.isPinned)
        assertEquals(streaming.messageNodes, session.state.value.messageNodes)
    }

    @Test
    fun `returning to a generating conversation retains reasoning and partial reply`() = runBlocking {
        val id = Uuid.random()
        val persisted = Conversation.ofId(id)
        val session = ConversationSession(id, persisted, this, FakeConversationStore(), {})
        session.initialize(load = { persisted })
        val streaming = session.update { it.updateCurrentMessages(listOf(partialReply())) }

        val otherId = Uuid.random()
        val other = ConversationSession(otherId, Conversation.ofId(otherId), this, FakeConversationStore(), {})
        other.initialize(load = { Conversation.ofId(otherId, assistantId = Uuid.random()) })

        // Android 重新创建 ViewModel、Web 重连及再次发送都会调用初始化。
        repeat(3) { session.initialize(load = { error("Must not reload an active session") }) }
        assertEquals(streaming, session.state.value)
        assertTrue(other.state.value.currentMessages.isEmpty())
    }

    @Test
    fun `concurrent initialization loads once without blocking another conversation`() = runBlocking {
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, FakeConversationStore(), {})
        val loaded = CompletableDeferred<Conversation>()
        var loads = 0
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize(load = {
                loads++
                loaded.await()
            })
        }
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize(load = {
                loads++
                initial
            })
        }
        val otherId = Uuid.random()
        val other = ConversationSession(otherId, Conversation.ofId(otherId), this, FakeConversationStore(), {})
        other.initialize(load = { Conversation.ofId(otherId) })
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
        loaded.complete(persisted)
        first.join()
        second.join()
        assertEquals(1, loads)
        assertEquals(persisted, session.state.value)
    }

    @Test
    fun `updates are rejected until the conversation is loaded`() = runBlocking {
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), this, FakeConversationStore(), {})
        val loaded = CompletableDeferred<Conversation>()
        val loading = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize(load = { loaded.await() })
        }
        // 在占位的空会话上修改再落库会覆盖掉数据库里的历史
        assertTrue(runCatching { session.update { it.updateCurrentMessages(listOf(partialReply())) } }
            .exceptionOrNull() is IllegalStateException)

        val persisted = Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("history")))
        loaded.complete(persisted)
        loading.join()
        assertEquals(persisted, session.state.value)
        session.update { it.copy(title = "edited") }
        assertEquals("edited", session.state.value.title)
    }

    @Test
    fun `cancelled initial load can be retried`() = runBlocking {
        val id = Uuid.random()
        val initial = Conversation.ofId(id)
        val session = ConversationSession(id, initial, this, FakeConversationStore(), {})
        val loading = launch(start = CoroutineStart.UNDISPATCHED) {
            session.initialize(load = { awaitCancellation() })
        }
        loading.cancel()
        loading.join()
        val persisted = initial.updateCurrentMessages(listOf(UIMessage.user("history")))
        session.initialize(load = { persisted })
        assertEquals(persisted, session.state.value)
    }

    @Test
    fun `flush writes the latest state and skips when nothing changed`() = runBlocking {
        val id = Uuid.random()
        val stored = Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("history")))
        val store = FakeConversationStore(stored)
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })

        session.flush()
        assertTrue(store.writes.isEmpty())

        session.update { it.copy(title = "first") }
        session.update { it.copy(title = "second") }
        session.flush()
        session.flush()
        assertEquals(1, store.writes.size)
        val (previous, current) = store.writes.single()
        // 文件回收按上次落库的状态和这次的差集计算
        assertSame(stored, previous)
        assertEquals("second", current.title)
    }

    @Test
    fun `a flush waiting for an earlier write still persists the newest state`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("a"))))
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })
        val allowWrite = CompletableDeferred<Unit>()
        store.beforeWrite = { allowWrite.await() }

        session.update { it.copy(title = "first") }
        val first = launch(start = CoroutineStart.UNDISPATCHED) { session.flush() }
        session.update { it.copy(title = "second") }
        val second = launch(start = CoroutineStart.UNDISPATCHED) { session.flush() }
        allowWrite.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("first", "second"), store.writes.map { it.second.title })
        assertEquals("second", store.persisted?.title)
    }

    @Test
    fun `column updates on a clean session do not leave a full write behind`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("a"))))
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })

        session.updateMetadata(transform = { it.copy(title = "generated") }, persist = {})
        session.flush()
        assertTrue(store.writes.isEmpty())

        // 有只改了内存的内容时，按列落库不能把它标记成已写入
        session.update { it.copy(chatSuggestions = listOf("next")) }
        session.updateMetadata(transform = { it.copy(isPinned = true) }, persist = {})
        session.flush()
        assertEquals(listOf("next"), store.writes.single().second.chatSuggestions)
    }

    @Test
    fun `new conversation is not written until it has content`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore()
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { null }, create = { Conversation.ofId(id, newConversation = true) })

        session.flush()
        assertTrue(store.writes.isEmpty())

        session.update { it.updateCurrentMessages(listOf(UIMessage.user("hello"))) }
        session.flush()
        assertNull(store.writes.single().first)
        session.update { it.copy(title = "named") }
        session.flush()
        assertEquals("hello", store.writes.last().first?.currentMessages?.single()?.toText())
    }

    @Test
    fun `idle eviction persists memory-only edits of a stored conversation`() = runTest {
        val id = Uuid.random()
        val store = FakeConversationStore(Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.user("a"))))
        var evicted = false
        val session = ConversationSession(id, Conversation.ofId(id), backgroundScope, store, { evicted = true })
        session.initialize(load = { store.persisted })
        session.update { it.copy(title = "translated") }
        session.acquire()
        session.release()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(evicted)
        assertEquals("translated", store.persisted?.title)
    }

    @Test
    fun `idle eviction does not create a conversation that was only opened`() = runTest {
        val id = Uuid.random()
        val store = FakeConversationStore()
        var evicted = false
        val session = ConversationSession(id, Conversation.ofId(id), backgroundScope, store, { evicted = true })
        session.initialize(
            load = { null },
            create = { Conversation.ofId(id).updateCurrentMessages(listOf(UIMessage.assistant("preset greeting"))) },
        )
        session.update { it.copy(workspaceCwd = "/workspace/project") }
        session.acquire()
        session.release()
        advanceTimeBy(6_000)
        runCurrent()
        assertTrue(evicted)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun `failed stream persists partial content for a recreated session`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(Conversation.ofId(id))
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })
        val failure = IllegalStateException("connection lost")
        val result = runCatching {
            flow {
                emit(partialReply())
                throw failure
            }.onCompletion {
                session.finishGeneration()
            }.collect { message ->
                session.update { it.updateCurrentMessages(listOf(message)) }
                // 和 applyReply 一样记下本轮正在写的消息：生成结束时只收尾这一条（W-058）
                session.replyMessageId.value = message.id
            }
        }
        assertSame(failure, result.exceptionOrNull())
        val reopened = ConversationSession(id, Conversation.ofId(id), this, FakeConversationStore(), {})
        reopened.initialize(load = { store.persisted })
        val parts = reopened.state.value.currentMessages.single().parts
        assertEquals("thinking so far", (parts[0] as UIMessagePart.Reasoning).reasoning)
        assertNotNull((parts[0] as UIMessagePart.Reasoning).finishedAt)
        assertEquals(UIMessagePart.Text("partial reply"), parts[1])
        val tool = parts[2] as UIMessagePart.Tool
        assertEquals("edit_file", tool.toolName)
        assertEquals(listOf(UIMessagePart.Text("file updated")), tool.output)
    }

    @Test
    fun `cancelled generation waits for partial content to be persisted`() = runBlocking {
        val id = Uuid.random()
        val store = FakeConversationStore(Conversation.ofId(id))
        val session = ConversationSession(id, Conversation.ofId(id), this, store, {})
        session.initialize(load = { store.persisted })
        val saving = CompletableDeferred<Unit>()
        val allowSave = CompletableDeferred<Unit>()
        store.beforeWrite = {
            saving.complete(Unit)
            allowSave.await()
        }
        val generation = launch(start = CoroutineStart.UNDISPATCHED) {
            flow {
                emit(partialReply())
                awaitCancellation()
            }.onCompletion {
                session.finishGeneration()
            }.collect { message ->
                session.update { it.updateCurrentMessages(listOf(message)) }
            }
        }
        session.setJob(generation)
        generation.cancel()
        saving.await()
        assertFalse(generation.isCompleted)
        assertTrue(session.isInUse)
        allowSave.complete(Unit)
        generation.join()
        assertNull(session.getJob())
        val persisted = store.persisted!!
        assertEquals(session.state.value, persisted)
        assertEquals("partial reply", (persisted.currentMessages.single().parts[1] as UIMessagePart.Text).text)
        session.cleanup()
    }

    private fun partialReply() = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Reasoning("thinking so far", finishedAt = null),
            UIMessagePart.Text("partial reply"),
            UIMessagePart.Tool(
                toolCallId = "edit-1",
                toolName = "edit_file",
                input = "{}",
                output = listOf(UIMessagePart.Text("file updated")),
            ),
        ),
    )

    @Test
    fun `replaced job finishing late cannot clear successor or advance queue`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<Throwable?>()
        val id = Uuid.random()
        val session = ConversationSession(
            id,
            Conversation.ofId(id),
            scope,
            FakeConversationStore(),
            {},
            { _, cause -> completions.add(cause) }
        )
        try {
            val releaseOld = CompletableDeferred<Unit>()
            val releaseNew = CompletableDeferred<Unit>()
            val old = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { releaseOld.await() }
                }
            }
            session.setJob(old)
            val successor = scope.launch(start = CoroutineStart.LAZY) { releaseNew.await() }
            session.setJob(successor)

            releaseOld.complete(Unit)
            old.join()
            assertSame(successor, session.getJob())
            assertTrue(completions.isEmpty())

            releaseNew.complete(Unit)
            successor.join()
            assertNull(session.getJob())
            assertEquals(listOf<Throwable?>(null), completions)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `instant completion does not leave a stale generation job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, FakeConversationStore(), {})
        try {
            session.setJob(scope.launch(start = CoroutineStart.LAZY) {})
            assertNull(session.getJob())
            assertFalse(session.isGenerating)
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `startJobIfIdle neither starts nor cancels while a job is running`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, FakeConversationStore(), {})
        try {
            val running = scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(running)
            var created = 0
            val second = session.startJobIfIdle {
                created++
                scope.launch(start = CoroutineStart.LAZY) { }
            }
            assertNull(second)
            assertEquals("忙的时候连任务都不该创建", 0, created)
            assertTrue("闹钟轮不能取消正在进行的任务", running.isActive)
            assertSame(running, session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    /** R2b：没有审批入口，老数据里留着的 Pending 工具调用不能让会话一直「忙」 */
    @Test
    fun `a leftover pending tool call does not keep the session busy`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = Uuid.random()
        val pending = UIMessage(
            role = MessageRole.ASSISTANT,
            parts = listOf(
                UIMessagePart.Tool(
                    toolCallId = "call-1",
                    toolName = "old_tool",
                    input = "{}",
                    approvalState = me.rerere.ai.ui.ToolApprovalState.Pending,
                )
            ),
        )
        val conversation = Conversation.ofId(id).copy(
            messageNodes = listOf(UIMessage.user("hi"), pending).map {
                me.rerere.rikkahub.data.model.MessageNode(messages = listOf(it))
            }
        )
        val session = ConversationSession(id, conversation, scope, FakeConversationStore(), {})
        try {
            val job = session.startJobIfIdle { scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() } }
            assertNotNull(job)
            assertSame(job, session.getJob())
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `concurrent startJobIfIdle calls start exactly one job`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val id = Uuid.random()
        val session = ConversationSession(id, Conversation.ofId(id), scope, FakeConversationStore(), {})
        try {
            repeat(50) {
                val gate = java.util.concurrent.CountDownLatch(1)
                val started = java.util.concurrent.atomic.AtomicInteger()
                val jobs = java.util.Collections.synchronizedList(mutableListOf<kotlinx.coroutines.Job>())
                val threads = List(4) {
                    Thread {
                        gate.await()
                        session.startJobIfIdle {
                            started.incrementAndGet()
                            scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }.also { jobs += it }
                        }
                    }.apply { start() }
                }
                gate.countDown()
                threads.forEach { it.join() }
                assertEquals(1, started.get())
                assertTrue(jobs.single().isActive)
                session.cleanup()
            }
        } finally {
            session.cleanup()
            scope.cancel()
        }
    }
}
