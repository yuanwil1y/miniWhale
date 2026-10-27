package me.rerere.rikkahub.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

/** W-047：一轮生成的结束事件只发一次，取消时也一定发出去 */
class TurnEndSignalTest {

    @Test
    fun `cancelled turn reports its end exactly once`() = runBlocking {
        val sent = mutableListOf<String?>()
        val signal = TurnEndSignal { sent += it }
        // 和 runTurn 一样：finally 里带预览发一次，取消异常的 catch 里再补一次
        val turn = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    signal.ended("预览")
                }
            } catch (e: CancellationException) {
                signal.ended(null)
                throw e
            }
        }
        turn.cancelAndJoin()
        assertEquals(listOf<String?>("预览"), sent)
    }

    @Test
    fun `end is still delivered when the sender suspends inside a cancelled coroutine`() = runBlocking {
        val sent = mutableListOf<String?>()
        val signal = TurnEndSignal { preview ->
            yield() // 事件总线缓冲满时 emit 会挂起
            sent += preview
        }
        val turn = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                CompletableDeferred<Unit>().await()
            } catch (e: CancellationException) {
                signal.ended(null)
                throw e
            }
        }
        turn.cancelAndJoin()
        assertEquals(listOf<String?>(null), sent)
    }

    @Test
    fun `concurrent reports send one event`() = runBlocking {
        val sent = mutableListOf<String?>()
        val signal = TurnEndSignal { sent += it }
        coroutineScope {
            List(4) { i -> async { signal.ended("p$i") } }.forEach { it.await() }
        }
        assertEquals(1, sent.size)
    }
}
