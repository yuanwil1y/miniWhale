package whaleui.nightsky.ltd.ai

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 兼容 Transformer（任务书 1.6）与回复清理 Transformer（任务书 1.5）的单元测试。
 *
 * 直接测 internal 纯函数，不依赖 Android Context。
 * 覆盖任务书 1.7 要求：「兼容 Transformer：各种图片和语音的位置」。
 */
class WhaleTransformersTest {

    private fun user(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.USER, parts = parts.toList())
    private fun assistant(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.ASSISTANT, parts = parts.toList())
    private fun system(vararg parts: UIMessagePart) = UIMessage(role = MessageRole.SYSTEM, parts = parts.toList())

    private fun UIMessage.texts(): List<String> =
        parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    // ---------------------------------------------------------- compatTransform

    @Test
    fun `user images are kept untouched`() {
        val messages = listOf(user(UIMessagePart.Text("看看这个"), UIMessagePart.Image("file:///photo.jpg")))
        val result = compatTransform(messages)
        assertEquals(messages, result)
        assertTrue(result.single().parts.any { it is UIMessagePart.Image })
    }

    @Test
    fun `assistant image becomes a text placeholder with the prompt`() {
        val image = UIMessagePart.Image(
            url = "file:///draw.png",
            metadata = buildJsonObject { put("prompt", JsonPrimitive("测试图片描述")) },
        )
        val result = compatTransform(listOf(assistant(image)))
        assertEquals(listOf("(你画了一张图:测试图片描述)"), result.single().texts())
        assertTrue(result.single().parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `assistant image without prompt still becomes a placeholder`() {
        val result = compatTransform(listOf(assistant(UIMessagePart.Image("file:///draw.png"))))
        assertEquals(listOf("(你画了一张图:)"), result.single().texts())
    }

    @Test
    fun `assistant audio becomes a text placeholder with the transcript`() {
        val audio = UIMessagePart.Audio(
            url = "file:///voice.mp3",
            metadata = buildJsonObject { put("text", JsonPrimitive("晚安")) },
        )
        val result = compatTransform(listOf(assistant(audio)))
        assertEquals(listOf("(你发了一条语音:晚安)"), result.single().texts())
        assertTrue(result.single().parts.none { it is UIMessagePart.Audio })
    }

    @Test
    fun `system message with an image is also replaced`() {
        val result = compatTransform(listOf(system(UIMessagePart.Image("file:///x.png"))))
        assertTrue(result.single().parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `tool output image becomes 'already sent to him' text`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-1",
            toolName = "draw",
            input = "{}",
            output = listOf(UIMessagePart.Text("画好了"), UIMessagePart.Image("file:///draw.png")),
        )
        val result = compatTransform(listOf(assistant(tool)))
        val outputs = (result.single().parts.single() as UIMessagePart.Tool).output
        assertEquals(listOf("画好了", "(图已经发给对方了)"), outputs.filterIsInstance<UIMessagePart.Text>().map { it.text })
        assertTrue(outputs.none { it is UIMessagePart.Image })
    }

    @Test
    fun `tool output audio becomes a voice placeholder`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-2",
            toolName = "text_to_speech",
            input = "{}",
            output = listOf(
                UIMessagePart.Audio(
                    url = "file:///v.mp3",
                    metadata = buildJsonObject { put("text", JsonPrimitive("在呢")) },
                )
            ),
        )
        val result = compatTransform(listOf(assistant(tool)))
        val outputs = (result.single().parts.single() as UIMessagePart.Tool).output
        assertEquals(listOf("(你发了一条语音:在呢)"), outputs.filterIsInstance<UIMessagePart.Text>().map { it.text })
    }

    @Test
    fun `tool without media outputs is left alone`() {
        val tool = UIMessagePart.Tool(
            toolCallId = "call-3",
            toolName = "weather",
            input = "{}",
            output = listOf(UIMessagePart.Text("晴")),
        )
        val result = compatTransform(listOf(assistant(tool)))
        assertEquals(listOf(UIMessagePart.Text("晴")), (result.single().parts.single() as UIMessagePart.Tool).output)
    }

    @Test
    fun `reasoning parts survive untouched`() {
        val reasoning = UIMessagePart.Reasoning(reasoning = "想一想")
        val result = compatTransform(listOf(assistant(reasoning, UIMessagePart.Text("嗯"))))
        assertTrue(result.single().parts.first() is UIMessagePart.Reasoning)
        assertEquals(listOf("嗯"), result.single().texts())
    }

    // ---------------------------------------------------------- finishReply

    @Test
    fun `finishReply applies the hermes cleanup`() {
        val message = assistant(UIMessagePart.Text("好耶😄\n/help\n（困）\n晚安"))
        assertEquals(listOf("好耶\n晚安"), finishReply(message).texts())
    }

    @Test
    fun `finishReply turns ／ into newlines`() {
        val message = assistant(UIMessagePart.Text("第一句／第二句"))
        assertEquals(listOf("第一句\n第二句"), finishReply(message).texts())
    }

    @Test
    fun `silent reply clears all parts`() {
        val message = assistant(UIMessagePart.Text("[SILENT]"))
        assertTrue(finishReply(message).parts.isEmpty())
    }

    @Test
    fun `silent marker on the last line also counts`() {
        val message = assistant(UIMessagePart.Text("算了\n[SILENT]"))
        assertTrue(finishReply(message).parts.isEmpty())
    }

    @Test
    fun `finishReply keeps non text parts and their order`() {
        val reasoning = UIMessagePart.Reasoning(reasoning = "想一想")
        val message = assistant(reasoning, UIMessagePart.Text("你好😄"))
        val result = finishReply(message)
        assertTrue(result.parts.first() is UIMessagePart.Reasoning)
        assertEquals(listOf("你好"), result.texts())
    }

    @Test
    fun `reply that becomes empty after cleanup clears parts`() {
        val message = assistant(UIMessagePart.Text("（困）"))
        assertTrue(finishReply(message).parts.isEmpty())
    }

    @Test
    fun `message without text is returned unchanged`() {
        val message = assistant(UIMessagePart.Image("file:///a.png"))
        assertEquals(message, finishReply(message))
    }

    // ---------------------------------------------------------- previewReply

    @Test
    fun `preview hides an unfinished action line`() {
        val message = assistant(UIMessagePart.Text("你好\n（还在"))
        assertEquals(listOf("你好"), previewReply(message).texts())
    }

    @Test
    fun `preview hides an unfinished silent marker`() {
        val message = assistant(UIMessagePart.Text("你好\n[SI"))
        assertEquals(listOf("你好"), previewReply(message).texts())
    }

    @Test
    fun `preview keeps a complete last line`() {
        val message = assistant(UIMessagePart.Text("你好\n晚安"))
        assertEquals(listOf("你好\n晚安"), previewReply(message).texts())
    }

    @Test
    fun `preview of a single half typed action is empty`() {
        val message = assistant(UIMessagePart.Text("（揉眼"))
        // 留下一个空 Text 部件（界面上什么都不显示），而不是把部件删掉
        assertTrue(previewReply(message).texts().all { it.isEmpty() })
    }

    @Test
    fun `streamingDisplay drops the trailing unfinished line`() {
        assertEquals("你好", streamingDisplay("你好\n(笑"))
        assertEquals("你好", streamingDisplay("你好\n[SI"))
        assertEquals("你好\n晚安", streamingDisplay("你好\n晚安"))
        assertEquals("", streamingDisplay("（困"))
    }

    @Test
    fun `preview of silent reply clears parts`() {
        val message = assistant(UIMessagePart.Text("[SILENT]"))
        assertTrue(previewReply(message).parts.isEmpty())
    }

    @Test
    fun `cleaning keeps the message id and role`() {
        val original = assistant(UIMessagePart.Text("你好😄"))
        val cleaned = finishReply(original)
        assertEquals(original.id, cleaned.id)
        assertEquals(original.role, cleaned.role)
        assertEquals(listOf("你好"), cleaned.texts())
    }

    // ---------------------------------------------------------- 表情（round5 3.3）

    private fun stickerPart(
        url: String = "file:///tmp/whale-online-test-stickers/01-1000001.webp",
        id: String = "01-1000001",
        description: String = "测试表情一",
        detail: String = "测试表情的详细说明。",
    ) = UIMessagePart.Image(
        url = url,
        metadata = whaleui.nightsky.ltd.sticker.stickerMetadata(id, description, detail),
    )

    private fun UIMessage.partsTexts(): List<String> =
        parts.filterIsInstance<UIMessagePart.Text>().map { it.text }

    @Test
    fun `her sticker becomes the fixed chinese line without the image`() {
        // 这条地址指向一个真实存在的临时文件，避免被当成「已删除」
        val temp = java.nio.file.Files.createTempFile("whale-sticker", ".webp").toFile()
        temp.deleteOnExit()
        val part = stickerPart(
            url = "file://" + temp.absolutePath,
            id = "01-1000001",
            description = "测试表情一",
        )
        val result = compatTransform(listOf(assistant(part)))
        assertEquals(1, result.size)
        assertEquals(
            listOf("(你发了一个表情:测试表情一,编号 01-1000001)"),
            result.single().partsTexts(),
        )
        assertTrue(result.single().parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `his sticker becomes the fixed chinese line with detail`() {
        val temp = java.nio.file.Files.createTempFile("whale-sticker", ".webp").toFile()
        temp.deleteOnExit()
        val part = stickerPart(
            url = "file://" + temp.absolutePath,
            id = "08-1000008",
            detail = "测试表情二的详细说明。",
        )
        val result = compatTransform(listOf(user(part)))
        assertEquals(
            listOf("(对方发来一个表情:测试表情二的详细说明。,编号 08-1000008)"),
            result.single().partsTexts(),
        )
        assertTrue(result.single().parts.none { it is UIMessagePart.Image })
    }

    @Test
    fun `sticker whose file was deleted becomes the deleted placeholder`() {
        val part = stickerPart(url = "file:///definitely/not/here/01-1000001.webp")
        assertEquals(
            listOf("(一个已删除的表情)"),
            compatTransform(listOf(assistant(part))).single().partsTexts(),
        )
    }

    @Test
    fun `sticker inside a tool output becomes text as well`() {
        val temp = java.nio.file.Files.createTempFile("whale-sticker", ".webp").toFile()
        temp.deleteOnExit()
        val tool = UIMessagePart.Tool(
            toolCallId = "call-sticker",
            toolName = "send_sticker",
            input = "{}",
            output = listOf(
                UIMessagePart.Text("""{"sent":true,"id":"01-1000001"}"""),
                stickerPart(url = "file://" + temp.absolutePath),
            ),
        )
        val result = compatTransform(listOf(assistant(tool)))
        val outputs = (result.single().parts.single() as UIMessagePart.Tool).output
        assertTrue(outputs.none { it is UIMessagePart.Image })
        assertTrue(outputs.filterIsInstance<UIMessagePart.Text>().any { it.text.startsWith("(你发了一个表情:") })
    }

    @Test
    fun `no assistant message keeps an image part after the transform`() {
        val temp = java.nio.file.Files.createTempFile("whale-sticker", ".webp").toFile()
        temp.deleteOnExit()
        val messages = listOf(
            user(UIMessagePart.Text("看看这个"), UIMessagePart.Image("file:///photo.jpg")),
            assistant(
                UIMessagePart.Text("好看"),
                UIMessagePart.Image("file:///draw.png"),
                stickerPart(url = "file://" + temp.absolutePath),
            ),
        )
        val result = compatTransform(messages)
        result.filter { it.role != MessageRole.USER }.forEach { message ->
            assertTrue("assistant 消息里不能有 image 部件", message.parts.none { it is UIMessagePart.Image })
        }
        // 他的图片仍然留着（DeepSeek 只允许 user 消息带图）
        assertTrue(result.first().parts.any { it is UIMessagePart.Image })
    }

    // ---------------------------------------------------------- 连发合并（round5 3.7）

    @Test
    fun `consecutive user messages are merged into one with newline joined text`() {
        val messages = listOf(
            user(UIMessagePart.Text("消息1")),
            user(UIMessagePart.Text("消息2")),
            user(UIMessagePart.Text("消息3")),
            assistant(UIMessagePart.Text("在")),
        )
        val result = compatTransform(messages)
        assertEquals(2, result.size)
        assertEquals(listOf("消息1\n消息2\n消息3"), result.first().partsTexts())
        assertEquals(MessageRole.USER, result.first().role)
    }

    @Test
    fun `merged user message keeps the parts order of text image sticker text`() {
        val temp = java.nio.file.Files.createTempFile("whale-sticker", ".webp").toFile()
        temp.deleteOnExit()
        val messages = listOf(
            user(UIMessagePart.Text("第一句")),
            user(UIMessagePart.Image("file:///photo.jpg")),
            user(stickerPart(url = "file://" + temp.absolutePath)),
            user(UIMessagePart.Text("最后一句")),
        )
        val result = compatTransform(messages)
        assertEquals(1, result.size)
        // 文字之间用 \n 连接（相邻的两段文字会被并成一段），图片留在原位，表情就地变成文字
        val texts = result.single().partsTexts()
        assertEquals(2, texts.size)
        assertEquals("第一句", texts[0])
        assertEquals(
            "(对方发来一个表情:测试表情的详细说明。,编号 01-1000001)\n最后一句",
            texts[1],
        )
        // 图片必须在两段文字之间
        val parts = result.single().parts
        val imageIndex = parts.indexOfFirst { it is UIMessagePart.Image }
        assertTrue(imageIndex > 0)
        assertTrue(imageIndex < parts.indexOfLast { it is UIMessagePart.Text })
    }

    @Test
    fun `merging is deterministic so the request prefix stays stable`() {
        val messages = listOf(
            user(UIMessagePart.Text("一")),
            user(UIMessagePart.Text("二")),
            assistant(UIMessagePart.Text("好")),
            user(UIMessagePart.Text("三")),
            user(UIMessagePart.Text("四")),
        )
        val first = compatTransform(messages)
        val second = compatTransform(messages)
        assertEquals(first, second)
        assertEquals(3, first.size)
    }

    @Test
    fun `the merged message uses the last message timestamp so the gap time is right`() {
        val first = user(UIMessagePart.Text("在吗"))
        val second = user(UIMessagePart.Text("睡了没"))
        val result = compatTransform(listOf(first, second))
        assertEquals(second.id, result.single().id)
        assertEquals(second.createdAt, result.single().createdAt)
    }

    @Test
    fun `single user message is untouched`() {
        val message = user(UIMessagePart.Text("只有一条"))
        assertEquals(listOf(message), compatTransform(listOf(message)))
    }

    @Test
    fun `user messages separated by her reply are not merged`() {
        val messages = listOf(
            user(UIMessagePart.Text("一")),
            assistant(UIMessagePart.Text("嗯")),
            user(UIMessagePart.Text("二")),
        )
        val result = compatTransform(messages)
        assertEquals(3, result.size)
    }
}
