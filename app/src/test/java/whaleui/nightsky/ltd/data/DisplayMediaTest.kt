package whaleui.nightsky.ltd.data

import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 回归：`text_to_speech` 的语音嵌在 Tool.output 里，之前界面上什么都不显示。 */
class DisplayMediaTest {

    @Test
    fun `audio nested in a tool output is surfaced`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "text_to_speech",
            input = "{}",
            output = listOf(UIMessagePart.Text("已发送"), UIMessagePart.Audio(url = "file:///a.mp3")),
        )
        val media = collectDisplayMedia(listOf(UIMessagePart.Text(""), tool))
        assertTrue(media.hasAny)
        assertEquals("file:///a.mp3", media.audio?.url)
    }

    @Test
    fun `image nested in a tool output is surfaced`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "draw",
            input = "{}",
            output = listOf(UIMessagePart.Image(url = "file:///a.png")),
        )
        val media = collectDisplayMedia(listOf(tool))
        assertEquals(listOf("file:///a.png"), media.images)
    }

    @Test
    fun `plain text and tool call without media has nothing to show`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "weather",
            input = "{}",
            output = listOf(UIMessagePart.Text("晴")),
        )
        val media = collectDisplayMedia(listOf(UIMessagePart.Text("你好"), tool))
        assertFalse(media.hasAny)
        assertNull(media.audio)
        assertTrue(media.images.isEmpty())
    }
}