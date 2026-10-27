package whaleui.nightsky.ltd.ui.chat

import kotlin.uuid.Uuid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.chat.MessageIds

/** 根治崩溃用的确定性 id：同一条消息永远同一个 id，且和乐观 id 不撞。 */
class MessageIdsTest {

    private val sample = Uuid.parse("2f905b31-7635-4ed0-b8e7-e81af75c055f")

    @Test
    fun `same uuid always maps to the same id`() {
        assertEquals(MessageIds.forUuid(sample), MessageIds.forUuid(sample))
        // 跨「进程」也一样：重新解析出来的 Uuid 还是同一个 id
        assertEquals(MessageIds.forUuid(sample), MessageIds.forUuid(Uuid.parse(sample.toString())))
    }

    @Test
    fun `ids are always negative so they cannot collide with optimistic ids`() {
        val uuids = List(2_000) { Uuid.random() } + sample
        assertTrue(uuids.all { MessageIds.forUuid(it) < 0 })
    }

    @Test
    fun `realistic samples do not collide`() {
        val uuids = List(10_000) { Uuid.random() }
        val ids = uuids.map { MessageIds.forUuid(it) }
        assertEquals(uuids.size, ids.toSet().size)
    }

    @Test
    fun `different uuids map to different ids`() {
        val a = MessageIds.forUuid(sample)
        val b = MessageIds.forUuid(Uuid.parse("2f905b31-7635-4ed0-b8e7-e81af75c0560"))
        assertTrue(a != b)
    }
}