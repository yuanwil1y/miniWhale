package whaleui.nightsky.ltd.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 任务书 6-2：每日名额要存盘，「重启」后不能白送一遍。 */
class WhaleDailyLimitStoreTest {

    /** 内存版 store：模拟进程重启（新建 WhaleDailyLimit 但 store 还在）。 */
    private class MemoryStore : WhaleDailyLimit.Store {
        var day: String? = null
        var counts: Map<String, Int> = emptyMap()
        override fun loadDay(): String? = day
        override fun loadCounts(): Map<String, Int> = counts
        override fun save(day: String, counts: Map<String, Int>) {
            this.day = day
            this.counts = counts
        }
    }

    @Test
    fun `counts survive a restart within the same day`() {
        val store = MemoryStore()
        val first = WhaleDailyLimit(searchLimit = 2, fetchLimit = 1, today = { "2026-10-26" }, store = store)
        assertTrue(first.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertTrue(first.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertFalse(first.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))

        // 「重启」：换一个实例，store 里还是今天的数据
        val second = WhaleDailyLimit(searchLimit = 2, fetchLimit = 1, today = { "2026-10-26" }, store = store)
        assertEquals(0, second.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertFalse(second.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
    }

    @Test
    fun `a new day resets the counters`() {
        val store = MemoryStore()
        val first = WhaleDailyLimit(searchLimit = 1, fetchLimit = 1, today = { "2026-10-26" }, store = store)
        assertTrue(first.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertFalse(first.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))

        val nextDay = WhaleDailyLimit(searchLimit = 1, fetchLimit = 1, today = { "2026-10-27" }, store = store)
        assertEquals(1, nextDay.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertTrue(nextDay.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
    }
}