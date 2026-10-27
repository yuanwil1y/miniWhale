package whaleui.nightsky.ltd.reply

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WhaleMergeSlices] 的单元测试。
 *
 * 只合并连续出现的 assistant 消息；相邻 Text 用 `\n` 拼成一个 Text，其余部件按原顺序追加；
 * user / system 原样保留，不跨角色合并。
 */
class WhaleMergeSlicesTest {

    private fun assistant(vararg parts: UIMessagePart) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = parts.toList(),
    )

    private fun user(content: String) = UIMessage(
        role = MessageRole.USER,
        parts = listOf(UIMessagePart.Text(content)),
    )

    private fun system(content: String) = UIMessage(
        role = MessageRole.SYSTEM,
        parts = listOf(UIMessagePart.Text(content)),
    )

    @Test
    fun `consecutive assistant messages are merged into one`() {
        val messages = listOf(
            user("在吗"),
            assistant(UIMessagePart.Text("一")),
            assistant(UIMessagePart.Text("二")),
            assistant(UIMessagePart.Text("三")),
        )
        val merged = WhaleMergeSlices.mergeAdjacentAssistant(messages)

        assertEquals(2, merged.size)
        assertEquals(MessageRole.USER, merged[0].role)
        assertEquals(MessageRole.ASSISTANT, merged[1].role)
        assertEquals(listOf(UIMessagePart.Text("一\n二\n三")), merged[1].parts)
        // 合并结果保留这一组第一条消息的 id
        assertEquals(messages[1].id, merged[1].id)
        // 只改请求，不改库：入参不变
        assertEquals(4, messages.size)
    }

    @Test
    fun `adjacent text parts are joined with a newline`() {
        val merged = WhaleMergeSlices.mergeAdjacentAssistant(
            listOf(
                assistant(UIMessagePart.Text("甲\n乙")),
                assistant(UIMessagePart.Text("丙")),
            )
        )

        assertEquals(1, merged.size)
        assertEquals(listOf(UIMessagePart.Text("甲\n乙\n丙")), merged[0].parts)
    }

    @Test
    fun `only adjacent text parts are joined`() {
        val search = UIMessagePart.Tool(toolCallId = "call_1", toolName = "search", input = "{}")
        val picture = UIMessagePart.Image("file:///a.png")
        val first = assistant(UIMessagePart.Text("查"), search)
        val second = assistant(UIMessagePart.Text("结果"), picture)

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(listOf(first, second))

        assertEquals(1, merged.size)
        assertEquals(
            listOf(UIMessagePart.Text("查"), search, UIMessagePart.Text("结果"), picture),
            merged[0].parts,
        )
    }

    @Test
    fun `user and assistant alternating are not merged`() {
        val messages = listOf(
            user("一"),
            assistant(UIMessagePart.Text("一")),
            user("二"),
            assistant(UIMessagePart.Text("二")),
        )

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(messages)

        assertEquals(messages, merged)
        assertEquals(4, merged.size)
    }

    @Test
    fun `system messages are kept as is`() {
        val sys = system("系统提示")
        val messages = listOf(sys, user("hi"), assistant(UIMessagePart.Text("答")))

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(messages)

        assertEquals(3, merged.size)
        assertSame(sys, merged[0])
    }

    @Test
    fun `a single assistant message is returned unchanged`() {
        val reply = assistant(UIMessagePart.Text("只有一条"))
        val merged = WhaleMergeSlices.mergeAdjacentAssistant(listOf(user("hi"), reply))

        assertEquals(2, merged.size)
        assertSame(reply, merged[1])
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(WhaleMergeSlices.mergeAdjacentAssistant(emptyList()).isEmpty())
    }

    @Test
    fun `all assistant messages collapse into one`() {
        val messages = (1..5).map { assistant(UIMessagePart.Text("第${it}条")) }

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(messages)

        assertEquals(1, merged.size)
        assertEquals(
            listOf(UIMessagePart.Text("第1条\n第2条\n第3条\n第4条\n第5条")),
            merged[0].parts,
        )
    }

    @Test
    fun `split bubbles merge back into the original request shape`() {
        // 一条回复被切片成多个气泡（每行一个 Text），组装请求时要还原成一条 assistant
        val lines = listOf("你好呀", "消息2", "我在这儿")
        val bubbles = lines.map { assistant(UIMessagePart.Text(it)) }

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(bubbles)

        assertEquals(1, merged.size)
        assertEquals(listOf(UIMessagePart.Text(lines.joinToString("\n"))), merged[0].parts)
    }

    @Test
    fun `tool order is preserved when merging`() {
        val search = UIMessagePart.Tool(toolCallId = "call_search", toolName = "search", input = "{}")
        val draw = UIMessagePart.Tool(toolCallId = "call_draw", toolName = "draw", input = "{}")
        val messages = listOf(
            assistant(UIMessagePart.Text("先查"), search),
            assistant(UIMessagePart.Text("再画"), draw),
        )

        val merged = WhaleMergeSlices.mergeAdjacentAssistant(messages)

        assertEquals(1, merged.size)
        assertEquals(
            listOf(
                UIMessagePart.Text("先查"),
                search,
                UIMessagePart.Text("再画"),
                draw,
            ),
            merged[0].parts,
        )
    }
}
