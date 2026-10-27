package whaleui.nightsky.ltd.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RunCatchingCancellableTest {

    @Test
    fun `success and ordinary failures are wrapped like runCatching`() {
        assertEquals(3, runCatchingCancellable { 1 + 2 }.getOrNull())
        val failure = runCatchingCancellable { error("坏了") }
        assertTrue(failure.isFailure)
        assertEquals("坏了", failure.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown instead of being wrapped`() {
        try {
            runCatchingCancellable { throw CancellationException("取消") }
            fail("CancellationException 应该原样抛出")
        } catch (e: CancellationException) {
            assertEquals("取消", e.message)
        }
    }

    @Test
    fun `a cancelled coroutine does not run the code after the call`() = runTest {
        var swallowedRanOn = false
        var cancellableRanOn = false
        val swallowing = launch {
            runCatching { delay(1_000) }
            swallowedRanOn = true // runCatching 吞掉取消：这一行照样执行（W-052 的问题）
        }
        val cancellable = launch {
            runCatchingCancellable { delay(1_000) }
            cancellableRanOn = true
        }
        runCurrent() // 两个协程都停在 delay 上
        swallowing.cancel()
        cancellable.cancel()
        advanceUntilIdle()
        assertTrue(swallowedRanOn)
        assertFalse(cancellableRanOn)
    }
}
