package whaleui.nightsky.ltd.whale

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连发合并的待发批次（任务书 3.7 + 第四节的 JVM 测试清单）。
 *
 * 用虚拟时间：`advanceTo(ms)` 推进时钟并执行到点的计时任务，不真的等 7 秒。
 */
class WhalePendingBatchTest {

    private class FakeScheduler : WhaleBatchScheduler {
        var now: Long = 0L
        private val tasks = mutableListOf<Task>()

        private class Task(val at: Long, val action: () -> Unit) {
            var cancelled = false
        }

        override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delayMs, action)
            tasks += task
            return { task.cancelled = true }
        }

        fun advanceTo(target: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= target }.minByOrNull { it.at } ?: break
                tasks.remove(next)
                now = next.at
                if (!next.cancelled) next.action()
            }
            now = target
        }

        fun pendingTasks(): Int = tasks.count { !it.cancelled }
    }

    private val conversationId = Uuid.random()
    private val firedAt = mutableListOf<Long>()

    private fun batch(
        scheduler: FakeScheduler,
        generating: () -> Boolean = { false },
        trigger: () -> Boolean = { firedAt += scheduler.now; true },
    ) = WhalePendingBatch(
        scheduler = scheduler,
        isGenerating = { generating() },
        clock = { scheduler.now },
    ) to trigger

    @Test
    fun `three sends inside the window produce exactly one request`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        val ids = List(3) { Uuid.random() }

        batch.record(conversationId, ids[0], trigger)
        scheduler.advanceTo(0)
        scheduler.advanceTo(2_000)
        batch.record(conversationId, ids[1], trigger)
        scheduler.advanceTo(5_000)
        batch.record(conversationId, ids[2], trigger)

        scheduler.advanceTo(11_999)
        assertTrue("还没到 7 秒窗口", firedAt.isEmpty())
        assertEquals(3, batch.size(conversationId))

        scheduler.advanceTo(12_000)
        assertEquals(listOf(12_000L), firedAt)
        assertFalse(batch.hasPending(conversationId))
    }

    @Test
    fun `a send after the window starts a second request`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(7_000)
        assertEquals(1, firedAt.size)

        scheduler.advanceTo(8_000)
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(15_000)
        assertEquals(2, firedAt.size)
        assertEquals(listOf(7_000L, 15_000L), firedAt)
    }

    @Test
    fun `each send restarts the timer`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(6_000)
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(12_999)
        assertTrue(firedAt.isEmpty())
        scheduler.advanceTo(13_000)
        assertEquals(1, firedAt.size)
    }

    @Test
    fun `a send while she is replying fires right after her turn without waiting again`() {
        val scheduler = FakeScheduler()
        var generating = true
        val (batch, trigger) = batch(scheduler, generating = { generating })
        batch.record(conversationId, Uuid.random(), trigger)

        // 7 秒到了，但她还在回 → 不打断，记成待触发
        scheduler.advanceTo(7_000)
        assertTrue(firedAt.isEmpty())
        assertTrue(batch.hasPending(conversationId))

        // 她这一轮结束 → 立刻触发（不再等 7 秒）
        generating = false
        assertTrue(batch.onGenerationFinished(conversationId))
        assertEquals(listOf(7_000L), firedAt)
        assertFalse(batch.hasPending(conversationId))
    }

    @Test
    fun `a second send while she is replying is included in the same next request`() {
        val scheduler = FakeScheduler()
        var generating = true
        val (batch, trigger) = batch(scheduler, generating = { generating })
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(7_000) // 第一个窗口到，她在回 → 待触发
        scheduler.advanceTo(9_000)
        batch.record(conversationId, Uuid.random(), trigger) // 又发一条，重新计时

        generating = false
        assertTrue(batch.onGenerationFinished(conversationId))
        assertEquals(listOf(9_000L), firedAt)
    }

    @Test
    fun `deleting a message removes it from the batch and an empty batch cancels the timer`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        val first = Uuid.random()
        val second = Uuid.random()
        batch.record(conversationId, first, trigger)
        batch.record(conversationId, second, trigger)
        assertEquals(2, batch.size(conversationId))

        assertTrue(batch.remove(conversationId, first))
        assertEquals(1, batch.size(conversationId))
        assertTrue(batch.remove(conversationId, second))
        assertEquals(0, batch.size(conversationId))
        assertFalse(batch.hasPending(conversationId))

        scheduler.advanceTo(30_000)
        assertTrue("批次空了就不该再触发", firedAt.isEmpty())
    }

    @Test
    fun `removing an unknown message is a no-op`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        batch.record(conversationId, Uuid.random(), trigger)
        assertFalse(batch.remove(conversationId, Uuid.random()))
        assertEquals(1, batch.size(conversationId))
    }

    @Test
    fun `flushNow sends the batch immediately for alarm turns`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(1_000)

        assertTrue(batch.flushNow(conversationId))
        assertEquals(listOf(1_000L), firedAt)
        assertFalse(batch.hasPending(conversationId))
        // 计时器已经取消，不会二次触发
        scheduler.advanceTo(20_000)
        assertEquals(1, firedAt.size)
    }

    @Test
    fun `clear drops everything without firing`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        batch.record(conversationId, Uuid.random(), trigger)
        batch.clear(conversationId)
        scheduler.advanceTo(20_000)
        assertTrue(firedAt.isEmpty())
        assertFalse(batch.hasPending(conversationId))
    }

    @Test
    fun `markPending for a race at fire time keeps the batch for after the turn`() {
        val scheduler = FakeScheduler()
        var generating = false
        val (batch, trigger) = batch(scheduler, generating = { generating })
        val id = Uuid.random()
        batch.record(conversationId, id, trigger)
        // 计时到之前有人抢先开始生成：批次仍在，标记为待触发
        generating = true
        batch.markPending(conversationId)
        generating = false
        assertTrue(batch.onGenerationFinished(conversationId))
        assertEquals(1, firedAt.size)
    }

    @Test
    fun `different conversations do not share a batch`() {
        val scheduler = FakeScheduler()
        val (batch, trigger) = batch(scheduler)
        val other = Uuid.random()
        batch.record(conversationId, Uuid.random(), trigger)
        batch.record(other, Uuid.random(), trigger)
        assertEquals(1, batch.size(conversationId))
        assertEquals(1, batch.size(other))
        batch.flushNow(conversationId)
        assertTrue(batch.hasPending(other))
    }

    // ---------------------------------------------------------------- W-011

    @Test
    fun `flushNow while she is replying keeps the batch and fires again after her turn`() {
        val scheduler = FakeScheduler()
        val (batch, _) = batch(scheduler)
        var calls = 0
        // 第一次触发时她正在回：ChatService 没能启动这一轮
        val trigger: () -> Boolean = { calls++; calls > 1 }
        val id = Uuid.random()
        batch.record(conversationId, id, trigger)
        batch.markPending(conversationId)

        assertFalse(batch.flushNow(conversationId))
        assertTrue("没启动的批次不能被清空", batch.hasPending(conversationId))
        assertTrue(batch.onGenerationFinished(conversationId))

        assertEquals("第一次没启动，她这一轮结束后必须再触发一次", 2, calls)
        assertFalse(batch.hasPending(conversationId))
    }

    @Test
    fun `window elapsing while a turn starts concurrently keeps the batch`() {
        val scheduler = FakeScheduler()
        val (batch, _) = batch(scheduler)
        var calls = 0
        // 锁外触发时恰好有任务开始（或在等工具审批）
        val trigger: () -> Boolean = { calls++; calls > 1 }
        batch.record(conversationId, Uuid.random(), trigger)
        scheduler.advanceTo(7_000)
        assertTrue("没启动的批次不能被清空", batch.hasPending(conversationId))
        batch.onGenerationFinished(conversationId)
        assertEquals(2, calls)
    }

    @Test
    fun `a message sent while the trigger runs stays for its own reply`() {
        val scheduler = FakeScheduler()
        val (batch, _) = batch(scheduler)
        val late = Uuid.random()
        lateinit var trigger: () -> Boolean
        trigger = {
            // 触发回调执行期间（锁外）他又发了一条
            batch.record(conversationId, late, trigger)
            true
        }
        batch.record(conversationId, Uuid.random(), trigger)
        assertTrue(batch.flushNow(conversationId))
        assertEquals(1, batch.size(conversationId))
    }

    @Test
    fun `a trigger that throws keeps the batch pending`() {
        val scheduler = FakeScheduler()
        val (batch, _) = batch(scheduler)
        var calls = 0
        val trigger: () -> Boolean = { calls++; if (calls == 1) error("boom") else true }
        batch.record(conversationId, Uuid.random(), trigger)
        assertFalse(batch.flushNow(conversationId))
        assertTrue(batch.onGenerationFinished(conversationId))
        assertEquals(2, calls)
    }
}
