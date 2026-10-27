package me.rerere.rikkahub.data.model

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.uuid.Uuid

class ConversationMessagesTest {
    @Test
    fun `deleting the only message of a node removes the node`() {
        val user = UIMessage.user("hi")
        val reply = UIMessage.assistant("hello")
        val conversation = conversationOf(user.toMessageNode(), reply.toMessageNode())

        val updated = conversation.deleteMessage(reply.id)!!

        assertEquals(listOf(user.id), updated.currentMessages.map { it.id })
    }

    @Test
    fun `deleting the selected last branch selects the previous one`() {
        val first = UIMessage.assistant("first")
        val second = UIMessage.assistant("second")
        val conversation = conversationOf(MessageNode(messages = listOf(first, second), selectIndex = 1))

        val updated = conversation.deleteMessage(second.id)!!

        assertEquals(listOf(first.id), updated.currentMessages.map { it.id })
    }

    @Test
    fun `deleting a missing message returns null`() {
        assertNull(conversationOf(UIMessage.user("hi").toMessageNode()).deleteMessage(Uuid.random()))
    }

    @Test
    fun `deleting everywhere removes the message from every node`() {
        // 同一条消息被写进多个节点时（界面 id 会去重成一个气泡），只删第一个节点会留下副本
        val repeated = UIMessage.assistant("same")
        val conversation = conversationOf(
            MessageNode(messages = listOf(repeated)),
            UIMessage.user("hi").toMessageNode(),
            MessageNode(messages = listOf(repeated.copy())),
        )

        val updated = conversation.deleteMessageEverywhere(repeated.id)!!

        assertTrue(updated.messageNodes.none { node -> node.messages.any { it.id == repeated.id } })
    }

    @Test
    fun `deleting everywhere keeps other messages and drops only emptied nodes`() {
        val reply = UIMessage.assistant("hello")
        val conversation = conversationOf(UIMessage.user("hi").toMessageNode(), reply.toMessageNode())

        val updated = conversation.deleteMessageEverywhere(reply.id)!!

        assertEquals(1, updated.messageNodes.size)
        assertEquals("hi", updated.currentMessages.single().toText())
    }

    @Test
    fun `deleting everywhere a missing message returns null`() {
        assertNull(
            conversationOf(UIMessage.user("hi").toMessageNode()).deleteMessageEverywhere(Uuid.random()),
        )
    }

    @Test
    fun `interrupted tools on the last message get a cancelled result`() {
        val conversation = conversationOf(
            UIMessage.user("run it").toMessageNode(),
            toolReply(ToolApprovalState.Approved).toMessageNode(),
        )

        val tool = conversation.cancelInterruptedTools()!!.currentMessages.last().getTools().single()

        assertTrue(tool.isExecuted)
        assertTrue((tool.output.single() as UIMessagePart.Text).text.contains("cancelled"))
    }

    @Test
    fun `nothing to cancel returns null`() {
        val conversation = conversationOf(UIMessage.user("hi").toMessageNode(), UIMessage.assistant("done").toMessageNode())

        assertNull(conversation.cancelInterruptedTools())
        assertNull(Conversation.ofId(Uuid.random()).cancelInterruptedTools())
    }

    private fun conversationOf(vararg nodes: MessageNode) =
        Conversation.ofId(Uuid.random()).copy(messageNodes = nodes.toList())

    private fun toolReply(approvalState: ToolApprovalState) = UIMessage(
        role = MessageRole.ASSISTANT,
        parts = listOf(
            UIMessagePart.Tool(
                toolCallId = Uuid.random().toString(),
                toolName = "workspace_shell",
                input = "{}",
                approvalState = approvalState,
            )
        ),
    )
}
