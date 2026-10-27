package whaleui.nightsky.ltd.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [WhaleDailyLimit] 的纯内存计数测试：同一天计数、超限、跨天清零。 */
class WhaleDailyLimitTest {

    @Test
    fun defaultsAreHundredSearchesAndFiftyFetches() {
        val limit = WhaleDailyLimit()
        assertEquals(100, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(50, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))

        repeat(100) { assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB)) }
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        // 两个工具分开计数，搜索用完不影响读网页。
        assertEquals(50, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))
    }

    @Test
    fun countsEachToolSeparatelyWithinTheSameDay() {
        val limit = WhaleDailyLimit(searchLimit = 2, fetchLimit = 1, today = { "2026-10-26" })
        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(1, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))

        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))
    }

    @Test
    fun resetsWhenTheDayChanges() {
        var day = "2026-10-26"
        val limit = WhaleDailyLimit(searchLimit = 2, fetchLimit = 1, today = { day })
        repeat(2) { assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB)) }
        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))

        day = "2026-10-27"
        assertEquals(2, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(1, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(1, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertTrue(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
    }

    @Test
    fun remainingNeverGoesNegativeAndUnknownToolFallsBackToSearchLimit() {
        val limit = WhaleDailyLimit(searchLimit = 0, fetchLimit = 0)
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_SEARCH_WEB))
        assertFalse(limit.tryConsume(WhaleDailyLimit.TOOL_READ_WEBPAGE))
        assertEquals(0, limit.remaining(WhaleDailyLimit.TOOL_READ_WEBPAGE))

        // 未登记的工具名按搜索的口径限制（工具类只传上面两个名字）。
        assertEquals(0, limit.remaining("unknown_tool"))
    }
}
