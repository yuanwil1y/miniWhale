package whaleui.nightsky.ltd.whale

import kotlin.uuid.Uuid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W-009：冷启动时同步从持久化的 id 恢复 [WhaleGate]，不覆盖 bootstrap 已经写好的值。 */
class WhaleGateTest {

    @After
    fun tearDown() {
        WhaleGate.assistantId = null
        WhaleGate.conversationId = null
    }

    @Test
    fun `restore fills an empty gate so the first alarm turn already knows her`() {
        val assistant = Uuid.random()
        val conversation = Uuid.random()
        assertFalse(WhaleGate.isWhaleAssistant(assistant))

        WhaleGate.restore(assistant, conversation)

        assertTrue(WhaleGate.isWhaleAssistant(assistant))
        assertTrue(WhaleGate.isWhaleConversation(conversation, null))
    }

    @Test
    fun `restore never overwrites what bootstrap already wrote`() {
        val fresh = Uuid.random()
        val freshConversation = Uuid.random()
        WhaleGate.assistantId = fresh
        WhaleGate.conversationId = freshConversation

        WhaleGate.restore(Uuid.random(), Uuid.random())

        assertEquals(fresh, WhaleGate.assistantId)
        assertEquals(freshConversation, WhaleGate.conversationId)
    }

    @Test
    fun `restore with nothing persisted leaves the gate closed`() {
        WhaleGate.restore(null, null)
        assertEquals(null, WhaleGate.assistantId)
        assertEquals(null, WhaleGate.conversationId)
    }
}
