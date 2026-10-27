package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationSessionManagerTest {
    private class Rig {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val completions = mutableListOf<ConversationSession>()
        val manager = ConversationSessionManager(
            scope = scope,
            store = FakeConversationStore(),
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { session, _ -> completions.add(session) },
        )

        fun close() {
            manager.cleanup()
            scope.cancel()
        }
    }

    @Test
    fun `same conversation shares a session while different conversations stay isolated`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val first = rig.manager.getOrCreate(id)
            val other = rig.manager.getOrCreate(Uuid.random())
            runBlocking { first.initialize(load = { Conversation.ofId(id) }) }
            first.update { it.copy(title = "first") }
            assertSame(first, rig.manager.getOrCreate(id))
            assertNotSame(first, other)
            assertEquals("", other.state.value.title)
            assertEquals(2, rig.manager.snapshot().size)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `idle removal waits for every UI or SSE reference to be released`() {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val session = rig.manager.acquire(id)
            assertSame(session, rig.manager.acquire(id))
            rig.manager.release(id)
            rig.manager.removeIfIdle(session)
            assertSame(session, rig.manager.get(id))
            rig.manager.release(id)
            rig.manager.removeIfIdle(session)
            assertNull(rig.manager.get(id))

            val replacement = rig.manager.getOrCreate(id)
            rig.manager.removeIfIdle(session)
            assertSame(replacement, rig.manager.get(id))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `cancelled scoped operation releases its session reference`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val operation = rig.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                rig.manager.withSession(id) { awaitCancellation() }
            }
            val session = requireNotNull(rig.manager.get(id))
            assertTrue(session.isInUse)
            operation.cancel()
            operation.join()
            assertFalse(session.isInUse)
            rig.manager.removeIfIdle(session)
            assertNull(rig.manager.get(id))
        } finally {
            rig.close()
        }
    }

    @Test
    fun `generation retains sessions after observers leave`() {
        val rig = Rig()
        try {
            val session = rig.manager.getOrCreate(Uuid.random())
            val job = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
            session.setJob(job)
            rig.manager.removeIfIdle(session)
            assertSame(session, rig.manager.get(session.id))
            job.cancel()
            assertEquals(listOf(session), rig.completions)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `job observer follows sessions created and recreated after subscription`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val observed = mutableListOf<Job?>()
            val observer = rig.scope.launch {
                rig.manager.getGenerationJobStateFlow(id).collect { observed.add(it) }
            }
            withTimeout(2_000) {
                rig.manager.getGenerationJobStateFlow(id).first { it == null }
                val first = rig.manager.getOrCreate(id)
                val firstJob = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                first.setJob(firstJob)
                rig.manager.getGenerationJobStateFlow(id).first { it === firstJob }
                assertSame(firstJob, observed.last())

                firstJob.cancel()
                firstJob.join()
                rig.manager.removeIfIdle(first)
                assertNull(observed.last())

                val second = rig.manager.getOrCreate(id)
                val secondJob = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                second.setJob(secondJob)
                rig.manager.getGenerationJobStateFlow(id).first { it === secondJob }
                assertSame(secondJob, observed.last())
                assertNotSame(first, second)
            }
            observer.cancel()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `conversation flow only emits loaded content, never the placeholder of an unloaded session`() = runBlocking {
        val rig = Rig()
        try {
            val id = Uuid.random()
            val observed = mutableListOf<Conversation>()
            val observer = rig.scope.launch { rig.manager.getConversationFlow(id).collect { observed.add(it) } }
            val session = rig.manager.getOrCreate(id)
            assertTrue("加载前的占位空会话发给界面会被当成消息全没了", observed.isEmpty())
            session.initialize(load = { Conversation.ofId(id).copy(title = "loaded") })
            assertEquals(listOf("loaded"), observed.map { it.title })
            observer.cancel()
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a pending batch keeps a session whose turn just ended from being evicted`() = runTest {
        var pendingBatch = false
        val manager = ConversationSessionManager(
            scope = backgroundScope,
            store = FakeConversationStore(),
            createInitialConversation = { Conversation.ofId(it) },
            onGenerationFinished = { _, _ -> },
            isRetained = { pendingBatch },
        )
        val id = Uuid.random()
        val session = manager.getOrCreate(id)
        session.initialize(load = { Conversation.ofId(id) })
        // 她这一轮结束：排上 5 秒后的空闲回收
        session.setJob(backgroundScope.launch(start = CoroutineStart.LAZY) { })
        runCurrent()
        // 4 秒后他发了一条，进了待发批次
        advanceTimeBy(4_000)
        pendingBatch = true
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(session.isInUse)
        assertSame("批次没发之前会话不能被回收", session, manager.get(id))

        // 批次发完（或被删光）之后照常可以回收
        pendingBatch = false
        assertFalse(session.isInUse)
        manager.removeIfIdle(session)
        assertNull(manager.get(id))
    }

    @Test
    fun `cleanup clears the registry and cancels every generation`() = runBlocking {
        val rig = Rig()
        try {
            val jobs = mutableMapOf<Uuid, Job?>()
            repeat(2) {
                val session = rig.manager.getOrCreate(Uuid.random())
                val job = rig.scope.launch(start = CoroutineStart.LAZY) { awaitCancellation() }
                jobs[session.id] = job
                session.setJob(job)
            }
            assertEquals(jobs.keys, rig.manager.snapshot().map { it.id }.toSet())
            rig.manager.cleanup()
            assertTrue(rig.manager.snapshot().isEmpty())
            jobs.values.forEach { assertTrue(it!!.isCancelled) }
        } finally {
            rig.close()
        }
    }
}
