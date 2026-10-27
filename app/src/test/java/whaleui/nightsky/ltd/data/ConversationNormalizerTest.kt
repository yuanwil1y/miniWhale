package whaleui.nightsky.ltd.data

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Test
import whaleui.nightsky.ltd.whale.ConversationNormalizer

/**
 * 回归上一轮的真事故：同一个 `UIMessage.id` 出现在 3 个节点里（内容逐渐变全），
 * 界面拿到重复 id → 崩溃 / 气泡内容串在一起。
 */
class ConversationNormalizerTest {

    private fun message(id: Uuid, text: String, role: MessageRole = MessageRole.ASSISTANT) =
        UIMessage(id = id, role = role, parts = listOf(UIMessagePart.Text(text)))

    private fun node(message: UIMessage) = MessageNode(id = Uuid.random(), messages = listOf(message))

    private fun conversation(vararg nodes: MessageNode) = Conversation(
        assistantId = Uuid.random(),
        title = "test",
        messageNodes = nodes.toList(),
    )

    @Test
    fun `three snapshots of the same message collapse into the richest one`() {
        val id = Uuid.random()
        val early = node(message(id, "怎么啦"))
        val middle = node(message(id, "消息1\n消息2"))
        val full = node(message(id, "消息1\n消息2\n消息3"))
        val other = node(message(Uuid.random(), "另外一条", MessageRole.USER))

        val cleaned = ConversationNormalizer.dedupeByMessageId(conversation(other, early, middle, full))

        assertEquals(2, cleaned.messageNodes.size)
        assertEquals("另外一条", cleaned.messageNodes[0].currentMessage.textOfFirstPart())
        assertEquals("消息1\n消息2\n消息3", cleaned.messageNodes[1].currentMessage.textOfFirstPart())
        assertEquals(1, ConversationNormalizer.countMessage(cleaned.messageNodes, id.toString()))
    }

    @Test
    fun `kept node sits at the position of its last occurrence`() {
        val id = Uuid.random()
        val a = node(message(Uuid.random(), "a", MessageRole.USER))
        val dup1 = node(message(id, "x"))
        val b = node(message(Uuid.random(), "b", MessageRole.USER))
        val dup2 = node(message(id, "x\ny"))

        val cleaned = ConversationNormalizer.dedupeByMessageId(conversation(a, dup1, b, dup2))

        assertEquals(listOf("a", "b", "x\ny"), cleaned.messageNodes.map { it.currentMessage.textOfFirstPart() })
    }

    @Test
    fun `dedupeMessage collapses only the given message and leaves other duplicates alone`() {
        val reply = Uuid.random()
        val older = Uuid.random()
        val o1 = node(message(older, "旧"))
        val o2 = node(message(older, "旧的"))
        val r1 = node(message(reply, "在"))
        val r2 = node(message(reply, "在的"))

        val cleaned = ConversationNormalizer.dedupeMessage(conversation(o1, o2, r1, r2), reply)

        assertEquals(listOf("旧", "旧的", "在的"), cleaned.messageNodes.map { it.currentMessage.textOfFirstPart() })
        val untouched = conversation(o1, r1)
        assertEquals(untouched, ConversationNormalizer.dedupeMessage(untouched, reply))
    }

    @Test
    fun `conversation without duplicates is returned untouched`() {
        val original = conversation(
            node(message(Uuid.random(), "a", MessageRole.USER)),
            node(message(Uuid.random(), "b")),
        )
        val cleaned = ConversationNormalizer.dedupeByMessageId(original)
        assertEquals(original.messageNodes.map { it.id }, cleaned.messageNodes.map { it.id })
    }

    private fun UIMessage.textOfFirstPart(): String =
        (parts.first() as UIMessagePart.Text).text
}