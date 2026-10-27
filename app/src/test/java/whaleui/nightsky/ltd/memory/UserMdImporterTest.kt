package whaleui.nightsky.ltd.memory

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/**
 * [UserMdImporter] 的单元测试：`§` 分条与「只合并不覆盖」。
 *
 * 所有测试文本都是编的假内容，与真实 USER.md 无关（绝不复制真实个人内容）。
 */
class UserMdImporterTest {

    private fun existingMemory(
        content: String,
        id: String = "existing-$content",
        category: MemoryCategory = MemoryCategory.AboutYou,
    ): Memory = Memory(
        id = id,
        content = content,
        category = category,
        createdAt = 1L,
        updatedAt = 1L,
        origin = MemoryOrigin.HerRecorded,
    )

    // ---------------- parse ----------------

    @Test
    fun `entries separated by a single separator line`() {
        assertEquals(
            listOf("测试用户住在测试城市", "测试用户喜欢测试用的食物"),
            UserMdImporter.parse("测试用户住在测试城市\n§\n测试用户喜欢测试用的食物"),
        )
    }

    @Test
    fun `separator on the first or last line is ignored`() {
        assertEquals(
            listOf("第一条假的记忆", "第二条假的记忆"),
            UserMdImporter.parse("§\n第一条假的记忆\n§\n第二条假的记忆\n§"),
        )
    }

    @Test
    fun `consecutive separators produce no empty entries`() {
        assertEquals(
            listOf("假甲", "假乙"),
            UserMdImporter.parse("假甲\n§\n§\n   \n§\n假乙"),
        )
    }

    @Test
    fun `separator must be alone on its line`() {
        // 行内出现 § 不算分隔，整段是一条
        assertEquals(
            listOf("一条里有§符号\n还有第二节"),
            UserMdImporter.parse("一条里有§符号\n还有第二节"),
        )
        // §§ 也不是单独一行的 §
        assertEquals(
            listOf("假甲\n§§\n假乙"),
            UserMdImporter.parse("假甲\n§§\n假乙"),
        )
    }

    @Test
    fun `separator line may have surrounding whitespace`() {
        assertEquals(
            listOf("假甲", "假乙"),
            UserMdImporter.parse("假甲\n   §   \n假乙"),
        )
    }

    @Test
    fun `entries are trimmed and blank entries are dropped`() {
        assertEquals(
            listOf("假甲", "假乙"),
            UserMdImporter.parse("   假甲   \n§\n\n§\n   \n§\n\t假乙\t"),
        )
    }

    @Test
    fun `duplicates keep the first occurrence only`() {
        assertEquals(
            listOf("假甲", "假乙"),
            UserMdImporter.parse("假甲\n§\n假乙\n§\n假甲\n§\n 假甲 "),
        )
    }

    @Test
    fun `multi line entry keeps its inner newlines`() {
        assertEquals(
            listOf("第一行假文本\n第二行假文本", "第三条假文本"),
            UserMdImporter.parse("第一行假文本\n第二行假文本\n§\n第三条假文本"),
        )
    }

    @Test
    fun `crlf input is parsed too`() {
        assertEquals(
            listOf("假甲", "假乙"),
            UserMdImporter.parse("假甲\r\n§\r\n假乙"),
        )
    }

    @Test
    fun `empty or separator only input parses to empty list`() {
        assertEquals(emptyList<String>(), UserMdImporter.parse(""))
        assertEquals(emptyList<String>(), UserMdImporter.parse("§"))
        assertEquals(emptyList<String>(), UserMdImporter.parse("§\n§\n   \n§"))
        assertEquals(emptyList<String>(), UserMdImporter.parse("   \n\t\n  "))
    }

    // ---------------- merge ----------------

    @Test
    fun `all incoming entries become new about you memories`() {
        val now = 1_700_000_000_000L
        val added = UserMdImporter.merge(
            emptyList(),
            listOf("测试用户住在测试城市", "测试用户喜欢测试用的食物"),
            now,
        )

        assertEquals(2, added.size)
        assertEquals(
            listOf("测试用户住在测试城市", "测试用户喜欢测试用的食物"),
            added.map { it.content },
        )
        added.forEach { memory ->
            assertEquals(MemoryCategory.AboutYou, memory.category)
            assertEquals(MemoryOrigin.UserAdded, memory.origin)
            assertEquals(now, memory.createdAt)
            assertEquals(now, memory.updatedAt)
            assertTrue(memory.id.isNotBlank())
            // 必须是合法的 UUID（不合法会抛异常，测试直接失败）
            UUID.fromString(memory.id)
        }
        assertEquals(2, added.map { it.id }.toSet().size)
    }

    @Test
    fun `existing same content is not added again`() {
        val added = UserMdImporter.merge(
            existing = listOf(existingMemory("测试用户住在测试城市")),
            incoming = listOf("测试用户住在测试城市", "测试用户喜欢测试用的食物"),
            now = 2L,
        )

        assertEquals(listOf("测试用户喜欢测试用的食物"), added.map { it.content })
    }

    @Test
    fun `content comparison trims existing content`() {
        val added = UserMdImporter.merge(
            existing = listOf(existingMemory("  测试用户住在测试城市  ")),
            incoming = listOf("测试用户住在测试城市"),
            now = 3L,
        )

        assertTrue(added.isEmpty())
    }

    @Test
    fun `existing memories are neither removed nor modified`() {
        val existing = listOf(
            existingMemory("旧的假记忆", id = "old-a"),
            existingMemory("另一条旧的假记忆", id = "old-b"),
        )
        val before = existing.toList()

        val added = UserMdImporter.merge(existing, listOf("新的测试记忆"), 4L)

        assertEquals(before, existing)
        assertEquals(listOf("新的测试记忆"), added.map { it.content })
        assertTrue(added.none { it.id == "old-a" || it.id == "old-b" })
    }

    @Test
    fun `duplicates inside incoming are skipped`() {
        val added = UserMdImporter.merge(emptyList(), listOf("假甲", "假甲", "假乙"), 5L)

        assertEquals(listOf("假甲", "假乙"), added.map { it.content })
    }

    @Test
    fun `blank incoming entries are skipped`() {
        val added = UserMdImporter.merge(emptyList(), listOf("", "   ", "假甲"), 6L)

        assertEquals(listOf("假甲"), added.map { it.content })
    }

    @Test
    fun `nothing to add returns an empty list`() {
        assertTrue(UserMdImporter.merge(emptyList(), emptyList(), 7L).isEmpty())
        assertTrue(
            UserMdImporter.merge(
                existing = listOf(existingMemory("假甲")),
                incoming = listOf("假甲", "   "),
                now = 8L,
            ).isEmpty()
        )
    }

    @Test
    fun `new memories keep the incoming order`() {
        val added = UserMdImporter.merge(emptyList(), listOf("假甲", "假乙", "假丙"), 9L)

        assertEquals(listOf("假甲", "假乙", "假丙"), added.map { it.content })
    }

    @Test
    fun `parsed user md can be merged directly`() {
        val raw = "测试用户住在测试城市\n§\n测试用户喜欢测试用的食物\n§\n测试用户住在测试城市"
        val parsed = UserMdImporter.parse(raw)
        val added = UserMdImporter.merge(emptyList(), parsed, 10L)

        assertEquals(
            listOf("测试用户住在测试城市", "测试用户喜欢测试用的食物"),
            added.map { it.content },
        )
    }
}
