package whaleui.nightsky.ltd.reply

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WhaleMessageSlicer] 的单元测试。
 *
 * 除了任务书列的场景（纯文本、多行、含 Reasoning、含工具、工具前后都有文字、
 * 含语音/图片工具输出），每个用例都验证切片不变式：
 * `joinSlices(sliceParts(parts))` 与 `parts` 在「相邻 Text 用 `\n` 连接」的规约下相等。
 */
class WhaleMessageSlicerTest {

    private fun text(value: String) = UIMessagePart.Text(value)

    private fun reasoning(value: String) = UIMessagePart.Reasoning(value)

    private fun tool(
        name: String,
        output: List<UIMessagePart> = emptyList(),
    ) = UIMessagePart.Tool(
        toolCallId = "call_$name",
        toolName = name,
        input = "{}",
        output = output,
    )

    private fun image(url: String = "file:///draw.png") = UIMessagePart.Image(url)

    private fun audio(url: String = "file:///voice.mp3") = UIMessagePart.Audio(url)

    /** 不变式里的「规约」：相邻 Text 用 `\n` 连接成一个 Text */
    private fun normalized(parts: List<UIMessagePart>): List<UIMessagePart> {
        val out = mutableListOf<UIMessagePart>()
        for (part in parts) {
            val last = out.lastOrNull()
            if (part is UIMessagePart.Text && last is UIMessagePart.Text) {
                out[out.lastIndex] = last.copy(text = last.text + "\n" + part.text)
            } else {
                out.add(part)
            }
        }
        return out
    }

    private fun assertRoundTrip(parts: List<UIMessagePart>) {
        val slices = WhaleMessageSlicer.sliceParts(parts)
        slices.forEach { slice ->
            assertTrue("切片不能为空: $slices", slice.isNotEmpty())
            assertFalse(
                "不能产生纯空白切片: $slices",
                slice.all { it is UIMessagePart.Text && it.text.isBlank() },
            )
        }
        assertEquals(
            "切片拼回来必须与原消息一致: $slices",
            normalized(parts),
            normalized(WhaleMessageSlicer.joinSlices(slices)),
        )
    }

    @Test
    fun `single line text is one slice`() {
        val parts = listOf(text("你好"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(1, slices.size)
        assertEquals(parts, slices[0])
        assertRoundTrip(parts)
    }

    @Test
    fun `each line of text becomes its own slice`() {
        val parts = listOf(text("第一行\n第二行\n第三行"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(3, slices.size)
        assertEquals(listOf(text("第一行")), slices[0])
        assertEquals(listOf(text("第二行")), slices[1])
        assertEquals(listOf(text("第三行")), slices[2])
        assertRoundTrip(parts)
    }

    @Test
    fun `reasoning stays at the front`() {
        val think = reasoning("先想一想")
        val parts = listOf(think, text("甲\n乙"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(2, slices.size)
        assertEquals(listOf(think, text("甲")), slices[0])
        assertEquals(listOf(text("乙")), slices[1])
        assertRoundTrip(parts)
    }

    @Test
    fun `tool follows the text before it`() {
        val search = tool("search")
        val parts = listOf(text("查一下"), search, text("好了"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(2, slices.size)
        assertEquals(listOf(text("查一下"), search), slices[0])
        assertEquals(listOf(text("好了")), slices[1])
        assertRoundTrip(parts)
    }

    @Test
    fun `tool follows the last line of multi line text`() {
        val search = tool("search")
        val parts = listOf(text("先说一句\n再说一句"), search, text("结论"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(3, slices.size)
        assertEquals(listOf(text("先说一句")), slices[0])
        assertEquals(listOf(text("再说一句"), search), slices[1])
        assertEquals(listOf(text("结论")), slices[2])
        assertRoundTrip(parts)
    }

    @Test
    fun `tool without preceding text joins the next text slice`() {
        val search = tool("search")
        val parts = listOf(search, text("结果如下"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(1, slices.size)
        assertEquals(listOf(search, text("结果如下")), slices[0])
        assertRoundTrip(parts)
    }

    @Test
    fun `tool alone becomes its own slice`() {
        val search = tool("search")
        val parts = listOf(search)
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(1, slices.size)
        assertEquals(parts, slices[0])
        assertRoundTrip(parts)
    }

    @Test
    fun `voice tool output gets its own slice`() {
        val voice = tool("text_to_speech", output = listOf(audio()))
        val parts = listOf(text("发你一段语音"), voice, text("听到了吗"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(3, slices.size)
        assertEquals(listOf(text("发你一段语音")), slices[0])
        assertEquals(listOf(voice), slices[1])
        assertEquals(listOf(text("听到了吗")), slices[2])
        assertRoundTrip(parts)
    }

    @Test
    fun `image tool output gets its own slice`() {
        val draw = tool("draw", output = listOf(image()))
        val parts = listOf(text("画好了"), draw)
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(2, slices.size)
        assertEquals(listOf(text("画好了")), slices[0])
        assertEquals(listOf(draw), slices[1])
        assertRoundTrip(parts)
    }

    @Test
    fun `plain media parts follow the text before them`() {
        val parts = listOf(text("看图"), image())
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(1, slices.size)
        assertEquals(parts, slices[0])
        assertRoundTrip(parts)
    }

    @Test
    fun `blank lines do not produce blank slices`() {
        val parts = listOf(text("a\n\nb"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(2, slices.size)
        assertRoundTrip(parts)
    }

    @Test
    fun `text with trailing newline round trips`() {
        val parts = listOf(text("a\n"))
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(1, slices.size)
        assertRoundTrip(parts)
    }

    @Test
    fun `blank only text is dropped`() {
        assertEquals(emptyList<List<UIMessagePart>>(), WhaleMessageSlicer.sliceParts(listOf(text("   \n\t "))))
        assertEquals(emptyList<List<UIMessagePart>>(), WhaleMessageSlicer.sliceParts(emptyList()))
    }

    @Test
    fun `full reply round trips through slicing`() {
        val think = reasoning("想一下")
        val search = tool("search")
        val voice = tool("text_to_speech", output = listOf(audio()))
        val parts = listOf(
            think,
            text("一\n二"),
            search,
            text("三"),
            voice,
            text("四\n五"),
        )
        val slices = WhaleMessageSlicer.sliceParts(parts)
        assertEquals(6, slices.size)
        assertEquals(listOf(think, text("一")), slices[0])
        assertEquals(listOf(text("二"), search), slices[1])
        assertEquals(listOf(text("三")), slices[2])
        assertEquals(listOf(voice), slices[3])
        assertEquals(listOf(text("四")), slices[4])
        assertEquals(listOf(text("五")), slices[5])
        assertRoundTrip(parts)
    }

    @Test
    fun `joined slices are the inverse of slicing`() {
        val parts = listOf(text("一"), text("二"), text("三"))
        assertEquals(
            listOf(text("一\n二\n三")),
            WhaleMessageSlicer.joinSlices(WhaleMessageSlicer.sliceParts(parts)),
        )
    }
}
