package me.rerere.rikkahub.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

/** W-020：生成用的前台服务拉不起来时，这一轮改由 expedited Worker 占住进程，直到这一轮结束。 */
class GenerationKeepAliveTest {
    private class Rig(var foregroundAllowed: Boolean) {
        val holds = GenerationHolds()
        val started = mutableListOf<Uuid>()
        val stopped = mutableListOf<Uuid>()
        val queued = mutableListOf<Uuid>()
        var enqueueFails = false
        val keepAlive = ForegroundOrWorkerKeepAlive(
            startForeground = { generationId, _ ->
                if (foregroundAllowed) started += generationId
                foregroundAllowed
            },
            stopForeground = { stopped += it },
            enqueueWorker = {
                if (enqueueFails) throw IllegalStateException("WorkManager is not initialized")
                queued += it
            },
            holds = holds,
            log = {},
        )
    }

    @Test
    fun `foreground service is used while the app may start it and no worker is queued`() {
        val rig = Rig(foregroundAllowed = true)
        val release = rig.keepAlive.hold(Uuid.random())
        assertEquals(1, rig.started.size)
        assertTrue(rig.queued.isEmpty())
        release()
        assertEquals(rig.started, rig.stopped)
    }

    @Test
    fun `when the foreground service is refused the turn is handed to a worker that waits for it to end`() = runTest {
        val rig = Rig(foregroundAllowed = false)
        val release = rig.keepAlive.hold(Uuid.random())
        val generationId = rig.queued.single()
        assertTrue("Worker 跑起来时要找得到这一轮", rig.holds.isOpen(generationId))

        val worker = async(start = CoroutineStart.UNDISPATCHED) { rig.holds.await(generationId) }
        runCurrent()
        assertFalse("这一轮没结束，Worker 不能先走", worker.isCompleted)

        release()
        runCurrent()
        assertTrue(worker.isCompleted)
        assertFalse(rig.holds.isOpen(generationId))
        assertTrue("没拉起过前台服务，也不该去停它", rig.stopped.isEmpty())
    }

    @Test
    fun `a worker for a turn that already ended or a restarted process returns at once`() = runTest {
        val holds = GenerationHolds()
        holds.await(Uuid.random())
    }

    @Test
    fun `failing to queue the worker does not break the turn`() {
        val rig = Rig(foregroundAllowed = false).apply { enqueueFails = true }
        val release = rig.keepAlive.hold(Uuid.random())
        release()
        assertTrue(rig.queued.isEmpty())
    }
}
