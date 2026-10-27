package whaleui.nightsky.ltd.whale

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W-021：进程被杀后再起来，他最后那条没人回的消息要补一轮回复。 */
class WhaleUnansweredReplyTest {

    private val zone = TimeZone.of("Asia/Shanghai")
    private val now = LocalDateTime(2026, 10, 26, 21, 0).toInstant(zone).toEpochMilliseconds()

    private fun msg(role: MessageRole, at: LocalDateTime) =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text("x")), createdAt = at)

    @Test
    fun `his recent unanswered message is picked up`() {
        val his = msg(MessageRole.USER, LocalDateTime(2026, 10, 26, 20, 58))
        val messages = listOf(msg(MessageRole.ASSISTANT, LocalDateTime(2026, 10, 26, 20, 0)), his)
        assertEquals(his.id, WhaleUnansweredReply.find(messages, now, zone = zone))
    }

    @Test
    fun `answered conversation needs nothing`() {
        val messages = listOf(
            msg(MessageRole.USER, LocalDateTime(2026, 10, 26, 20, 58)),
            msg(MessageRole.ASSISTANT, LocalDateTime(2026, 10, 26, 20, 59)),
        )
        assertNull(WhaleUnansweredReply.find(messages, now, zone = zone))
    }

    @Test
    fun `an old unanswered message is left alone`() {
        val messages = listOf(msg(MessageRole.USER, LocalDateTime(2026, 10, 26, 20, 29)))
        assertNull("超过 30 分钟不补，免得突然冒出一条很迟的回复", WhaleUnansweredReply.find(messages, now, zone = zone))
    }

    @Test
    fun `empty conversation needs nothing`() {
        assertNull(WhaleUnansweredReply.find(emptyList(), now, zone = zone))
    }
}
