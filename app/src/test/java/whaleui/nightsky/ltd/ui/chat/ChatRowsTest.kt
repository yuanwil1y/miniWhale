package whaleui.nightsky.ltd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageStatus

/** 日期胶囊 + 气泡分组（A：同一人、同一天、间隔 ≤ 5 分钟才合进一组）。 */
class ChatRowsTest {

    private fun message(
        id: Long,
        text: String = "x",
        fromHer: Boolean = true,
        at: Long = 0L,
        day: String = "今天",
    ) = ChatMessage(
        id = id,
        fromHer = fromHer,
        text = text,
        time = "14:00",
        dayLabel = day,
        createdAtMillis = at,
        status = MessageStatus.Read,
    )

    private val minute = 60_000L
    private val base = 1_800_000_000_000L

    private fun msgRows(vararg messages: ChatMessage) =
        buildChatRows(messages.toList()).filterIsInstance<ChatRowModel.Msg>()

    /** 回归：重复 id 会让 LazyColumn 的 key 冲突直接崩（Key "msg-1089" was already used）。 */
    @Test
    fun `duplicate message ids produce a single row`() {
        val rows = buildChatRows(
            listOf(
                message(1, "a"),
                message(2, "b"),
                message(2, "b"),
                message(3, "c"),
            ),
        )
        val messageRows = rows.filterIsInstance<ChatRowModel.Msg>()
        assertEquals(listOf(1L, 2L, 3L), messageRows.map { it.message.id })
    }

    @Test
    fun `distinct messages keep their order`() {
        val rows = msgRows(message(10, "a"), message(11, "b"))
        assertEquals(listOf(10L, 11L), rows.map { it.message.id })
    }

    @Test
    fun `same sender within five minutes is one group`() {
        val rows = msgRows(
            message(1, at = base),
            message(2, at = base + 2 * minute),
            message(3, at = base + 7 * minute),
        )
        assertEquals(listOf(true, false, false), rows.map { it.firstInGroup })
        assertEquals(listOf(false, false, true), rows.map { it.lastInGroup })
    }

    @Test
    fun `exactly five minutes still joins, one millisecond more splits`() {
        assertTrue(sameGroup(message(1, at = base), message(2, at = base + GROUP_WINDOW_MS)))
        assertFalse(sameGroup(message(1, at = base), message(2, at = base + GROUP_WINDOW_MS + 1)))
    }

    @Test
    fun `gap over five minutes starts a new group with tail on the earlier one`() {
        val rows = msgRows(
            message(1, at = base),
            message(2, at = base + 8 * minute),
        )
        assertTrue(rows[0].lastInGroup)
        assertTrue(rows[1].firstInGroup)
        assertEquals(BubbleGapBetweenGroups, rows[1].gapAbove)
    }

    @Test
    fun `sender change always splits`() {
        val rows = msgRows(
            message(1, fromHer = true, at = base),
            message(2, fromHer = false, at = base + 1_000),
        )
        assertTrue(rows[0].lastInGroup)
        assertTrue(rows[1].firstInGroup)
    }

    @Test
    fun `new day inserts a pill and splits even within five minutes`() {
        val rows = buildChatRows(
            listOf(
                message(1, at = base, day = "昨天"),
                message(2, at = base + minute, day = "今天"),
            ),
        )
        assertEquals(listOf("day-昨天", "msg-1", "day-今天", "msg-2"), rows.map { it.key })
        val second = rows[3] as ChatRowModel.Msg
        assertTrue(second.firstInGroup)
        assertEquals(0f, second.gapAbove.value)
    }

    @Test
    fun `unknown timestamps fall back to sender and day`() {
        val rows = msgRows(message(1), message(2, at = base))
        assertFalse(rows[1].firstInGroup)
        assertEquals(BubbleGapInGroup, rows[1].gapAbove)
    }

    @Test
    fun `inner gap is smaller than gap between groups`() {
        assertTrue(BubbleGapInGroup < BubbleGapBetweenGroups)
    }
}
