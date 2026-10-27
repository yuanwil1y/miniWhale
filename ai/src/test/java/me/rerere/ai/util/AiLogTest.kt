package me.rerere.ai.util

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** N-001：:ai 的日志默认关闭；打开时也只有元信息。 */
class AiLogTest {
    private val lines = mutableListOf<String>()
    private val originalSink = AiLog.sink

    @Before
    fun setUp() {
        AiLog.sink = { _, tag, message -> lines += "$tag $message" }
    }

    @After
    fun tearDown() {
        AiLog.sink = originalSink
        AiLog.enabled = false
    }

    @Test
    fun `disabled by default and does not even build the message`() {
        assertFalse(AiLog.enabled)
        var built = false
        AiLog.i("T") { built = true; "x" }
        AiLog.w("T") { built = true; "x" }
        AiLog.d("T") { built = true; "x" }
        assertFalse(built)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `enabled logs go to the sink`() {
        AiLog.enabled = true
        AiLog.i("T") { "hello" }
        assertEquals(listOf("T hello"), lines)
    }

    @Test
    fun `message summary carries only counts`() {
        val secret = "B2-秘密句子-不该出现在日志里"
        val messages = listOf(
            UIMessage.system("SOUL $secret"),
            UIMessage.user(secret),
            UIMessage(
                role = me.rerere.ai.core.MessageRole.ASSISTANT,
                parts = listOf(
                    UIMessagePart.Reasoning(reasoning = secret),
                    UIMessagePart.Text(secret),
                    UIMessagePart.Image(url = "file:///data/$secret.png"),
                ),
            ),
        )

        val summary = messages.logSummary()

        assertFalse(summary.contains("秘密"))
        assertFalse(summary.contains("SOUL"))
        assertEquals("messages=3 chars=${secret.length * 4 + 5} images=1 toolParts=0", summary)
    }

    @Test
    fun `exception is logged by type only`() {
        assertEquals("java.lang.IllegalStateException", IllegalStateException("用户原话").logType())
        assertEquals("null", (null as Throwable?).logType())
    }
}
