package whaleui.nightsky.ltd.alarm

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 看门狗判断「上一次到底发没发出去」用的 [hasVisibleReplySince]。 */
class AlarmReplyCheckTest {

    private val zone = TimeZone.of("Asia/Shanghai")
    private val started = LocalDateTime(2026, 10, 26, 10, 0)
    private val startedMillis = started.toInstant(zone).toEpochMilliseconds()

    private fun msg(role: MessageRole, text: String, at: LocalDateTime) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), createdAt = at)

    @Test
    fun `her message after the turn started counts`() {
        val messages = listOf(
            msg(MessageRole.USER, "早", LocalDateTime(2026, 10, 26, 9, 0)),
            msg(MessageRole.ASSISTANT, "早上好", LocalDateTime(2026, 10, 26, 10, 0, 20)),
        )
        assertTrue(messages.hasVisibleReplySince(startedMillis, zone))
    }

    @Test
    fun `her older messages and his new ones do not count`() {
        val messages = listOf(
            msg(MessageRole.ASSISTANT, "晚安呀", LocalDateTime(2026, 10, 25, 23, 0)),
            msg(MessageRole.USER, "早", LocalDateTime(2026, 10, 26, 10, 1)),
        )
        assertFalse(messages.hasVisibleReplySince(startedMillis, zone))
    }

    @Test
    fun `an empty half started message does not count`() {
        val messages = listOf(
            UIMessage(role = MessageRole.ASSISTANT, parts = emptyList(), createdAt = LocalDateTime(2026, 10, 26, 10, 1)),
        )
        assertFalse(messages.hasVisibleReplySince(startedMillis, zone))
    }
}
