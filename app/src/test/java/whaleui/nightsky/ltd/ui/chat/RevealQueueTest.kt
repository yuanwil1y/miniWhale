package whaleui.nightsky.ltd.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.MessageStatus

/** 待发队列：顺序、打断、替换。「历史不重播」走真实加载路径，见 ChatViewModelTest。 */
class RevealQueueTest {

    private fun message(id: Long, text: String) = ChatMessage(
        id = id,
        fromHer = true,
        text = text,
        time = "14:00",
        dayLabel = "今天",
        status = MessageStatus.Read,
    )

    @Test
    fun `reveals in the same order they were queued`() {
        val queue = RevealQueue()
        queue.enqueue(listOf(message(1, "a"), message(2, "b"), message(3, "c")))
        assertEquals(listOf("a", "b", "c"), generateSequence { queue.removeFirst()?.text }.toList())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `updated content replaces the queued bubble instead of adding a new one`() {
        val queue = RevealQueue()
        queue.enqueue(listOf(message(1, "半句")))
        queue.update(message(1, "完整的一句"))
        assertEquals(1, queue.size)
        assertEquals("完整的一句", queue.removeFirst()?.text)
    }

    @Test
    fun `interrupt flushes everything in order and leaves the queue empty`() {
        val queue = RevealQueue()
        queue.enqueue(listOf(message(1, "a"), message(2, "b"), message(3, "c")))
        queue.removeFirst()
        val rest = queue.flush()
        assertEquals(listOf("b", "c"), rest.map { it.text })
        assertTrue(queue.isEmpty)
        // 再来一次 flush 不应该重复出气泡
        assertTrue(queue.flush().isEmpty())
    }

    @Test
    fun `removed ids disappear from the queue`() {
        val queue = RevealQueue()
        queue.enqueue(listOf(message(1, "a"), message(2, "b")))
        queue.remove(listOf(1L))
        assertEquals(1, queue.size)
        assertEquals("b", queue.removeFirst()?.text)
    }
}
