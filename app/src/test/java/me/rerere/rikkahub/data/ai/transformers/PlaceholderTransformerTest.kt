package me.rerere.rikkahub.data.ai.transformers

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.ChatRequestFactory.Companion.appendToolPrompts
import org.junit.Assert.assertEquals
import org.junit.Test

/** 提示词第 10 批：小鲸鱼的占位只替换系统提示；第 1 批：空的工具 systemPrompt 不留空行。 */
class PlaceholderTransformerTest {

    private val values = mapOf("char" to "测试名字", "user" to "测试用户")

    private fun text(message: UIMessage) = (message.parts.single() as UIMessagePart.Text).text

    @Test
    fun `system only replaces the system prompt and leaves user text and injections alone`() {
        val messages = listOf(
            UIMessage.system("你叫 {{char}}，对方叫 {user}"),
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("我写了 {{char}} 和 {% raw %}"))),
            UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("{{user}}"))),
        )

        val result = PlaceholderTransformer.replaceInMessages(messages, systemOnly = true, values = values)

        assertEquals("你叫 测试名字，对方叫 测试用户", text(result[0]))
        assertEquals("我写了 {{char}} 和 {% raw %}", text(result[1]))
        assertEquals("{{user}}", text(result[2]))
    }

    @Test
    fun `other assistants keep replacing every message`() {
        val messages = listOf(
            UIMessage(role = MessageRole.USER, parts = listOf(UIMessagePart.Text("{{CHAR}}"))),
        )
        val result = PlaceholderTransformer.replaceInMessages(messages, systemOnly = false, values = values)
        assertEquals("测试名字", text(result[0]))
    }

    @Test
    fun `empty tool system prompts leave no blank lines`() {
        val result = StringBuilder("前面的系统提示").apply {
            appendToolPrompts(listOf("", "", "**技能**\n清单\n", "", "  "))
        }.toString()
        assertEquals("前面的系统提示\n\n**技能**\n清单", result)

        val onlyEmpty = StringBuilder("前面").apply { appendToolPrompts(listOf("", "")) }.toString()
        assertEquals("前面", onlyEmpty)
    }
}
