package whaleui.nightsky.ltd.data

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 聊天记录导入：Markdown 解析 + 合并进固定对话的纯逻辑。 */
class ChatMarkdownImportTest {

    private val sample = """
        # miniWhale

        ## 2026-10-25

        - [08:30] 她：今天多云
        - [13:54] 我：午饭吃了面条
        - [14:40] 她：下午记得带伞

        ## 2026-10-26

        - [08:30] 她：示例消息四
    """.trimIndent()

    @Test
    fun `parses dates, roles and minute timestamps`() {
        val messages = ChatMarkdownParser.parse(sample)
        assertEquals(4, messages.size)
        assertEquals(MessageRole.ASSISTANT, messages[0].role)
        assertEquals("今天多云", messages[0].text)
        assertEquals(LocalDateTime(2026, 10, 25, 8, 30), messages[0].at)
        assertEquals(MessageRole.USER, messages[1].role)
        assertEquals(LocalDateTime(2026, 10, 25, 13, 54), messages[1].at)
        assertEquals(LocalDateTime(2026, 10, 26, 8, 30), messages[3].at)
    }

    /** 旧版导出的文件一级标题是「# 小鲸鱼」，解析不看标题，结果和新标题一样。 */
    @Test
    fun `old app name heading parses the same`() {
        val old = sample.replace("# miniWhale", "# 小鲸鱼")
        assertEquals(ChatMarkdownParser.parse(sample), ChatMarkdownParser.parse(old))
        assertEquals(4, ChatMarkdownParser.parse(old).size)
    }

    @Test
    fun `messages before any date heading are ignored`() {
        val messages = ChatMarkdownParser.parse(
            """
            # 标题
            - [08:30] 她：没有日期
            ## 2026-10-25
            - [09:00] 她：有日期
            """.trimIndent()
        )
        assertEquals(listOf("有日期"), messages.map { it.text })
    }

    @Test
    fun `invalid date and time are ignored`() {
        val messages = ChatMarkdownParser.parse(
            """
            ## 2026-13-01
            - [09:00] 她：日期不对
            ## 2026-10-25
            - [25:00] 她：时间不对
            - [09:00] 她：正常
            """.trimIndent()
        )
        assertEquals(listOf("正常"), messages.map { it.text })
    }

    @Test
    fun `merge skips duplicates and keeps chronological order`() {
        val existing = listOf(
            node(
                message(
                    role = MessageRole.USER,
                    text = "旧消息",
                    at = LocalDateTime(2026, 10, 25, 8, 0),
                )
            )
        )
        val incoming = listOf(
            ImportedChatMessage(MessageRole.ASSISTANT, "醒啦", LocalDateTime(2026, 10, 25, 8, 30)),
            ImportedChatMessage(MessageRole.USER, "旧消息", LocalDateTime(2026, 10, 25, 8, 0)),
            ImportedChatMessage(MessageRole.USER, "我说话", LocalDateTime(2026, 10, 25, 7, 0)),
        )
        val result = ChatConversationMerge.merge(existing, incoming)
        assertEquals(2, result.added)
        assertEquals(1, result.skipped)
        assertEquals(
            listOf("我说话", "旧消息", "醒啦"),
            result.nodes.map { it.currentMessage.parts.filterIsInstance<UIMessagePart.Text>().single().text },
        )
    }

    @Test
    fun `merge keeps file order for messages in the same minute`() {
        val incoming = listOf(
            ImportedChatMessage(MessageRole.USER, "我", LocalDateTime(2026, 10, 25, 14, 40)),
            ImportedChatMessage(MessageRole.ASSISTANT, "她", LocalDateTime(2026, 10, 25, 14, 40)),
        )
        val result = ChatConversationMerge.merge(emptyList(), incoming)
        val roles = result.nodes.map { it.currentMessage.role }
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), roles)
    }

    @Test
    fun `dedupe matches exported minute against stored seconds`() {
        // 库里是 14:40:31，导出文件里只有 [14:40]；导回来不能重复。
        val existing = listOf(
            node(
                message(
                    role = MessageRole.ASSISTANT,
                    text = "同一句话",
                    at = LocalDateTime(2026, 10, 25, 14, 40, 31),
                )
            )
        )
        val incoming = listOf(
            ImportedChatMessage(MessageRole.ASSISTANT, "同一句话", LocalDateTime(2026, 10, 25, 14, 40)),
        )
        val result = ChatConversationMerge.merge(existing, incoming)
        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `dedupe folds newlines and spaces like the export does`() {
        // 库里的多行消息导出时被压成一行，导回来要认得是同一句。
        val existing = listOf(
            node(
                message(
                    role = MessageRole.ASSISTANT,
                    text = "示例第一行，\n示例第二行。",
                    at = LocalDateTime(2026, 10, 25, 20, 2, 5),
                )
            )
        )
        val incoming = listOf(
            ImportedChatMessage(
                MessageRole.ASSISTANT,
                "示例第一行， 示例第二行。",
                LocalDateTime(2026, 10, 25, 20, 2),
            ),
        )
        val result = ChatConversationMerge.merge(existing, incoming)
        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
    }

    private fun message(role: MessageRole, text: String, at: LocalDateTime): UIMessage =
        UIMessage(role = role, parts = listOf(UIMessagePart.Text(text)), createdAt = at)

    private fun node(message: UIMessage): MessageNode = MessageNode.of(message)
}
