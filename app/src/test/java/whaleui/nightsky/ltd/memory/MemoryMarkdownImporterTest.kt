package whaleui.nightsky.ltd.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/** 记忆导入的 Markdown 解析与去重（和 `MemoryRepository.exportAll()` 的格式一一对应）。 */
class MemoryMarkdownImporterTest {

    private val sample = """
        # 记忆

        ## 关于你

        - 测试记忆一（2026-10-25）

        ## 约定

        - 每天说晚安（2026-10-26）

        ## 她的

        - 联网检索走 search_web（2026-10-26） · 她记下的
    """.trimIndent()

    @Test
    fun `parses categories, dates and origin marker`() {
        val items = MemoryMarkdownImporter.parse(sample)
        assertEquals(3, items.size)
        assertEquals(MemoryCategory.AboutYou, items[0].category)
        assertEquals("测试记忆一", items[0].content)
        assertEquals(MemoryOrigin.UserAdded, items[0].origin)
        assertEquals(MemoryCategory.Agreement, items[1].category)
        assertEquals("每天说晚安", items[1].content)
        assertEquals(MemoryCategory.Hers, items[2].category)
        assertEquals("联网检索走 search_web", items[2].content)
        assertEquals(MemoryOrigin.HerRecorded, items[2].origin)
    }

    @Test
    fun `entries without heading default to about you`() {
        val items = MemoryMarkdownImporter.parse("- 一条没有分类的记忆")
        assertEquals(1, items.size)
        assertEquals(MemoryCategory.AboutYou, items.single().category)
        assertEquals("一条没有分类的记忆", items.single().content)
    }

    @Test
    fun `unknown heading and empty lines are tolerated`() {
        val items = MemoryMarkdownImporter.parse(
            """
            ## 随便一个标题
            - 假甲
            - 
            - 假乙
            """.trimIndent()
        )
        assertEquals(listOf("假甲", "假乙"), items.map { it.content })
        assertTrue(items.all { it.category == MemoryCategory.AboutYou })
    }

    @Test
    fun `plan skips content that already exists and duplicates inside the file`() {
        val existing = listOf(
            Memory(
                id = "old",
                content = "已经在的",
                category = MemoryCategory.AboutYou,
                createdAt = 0L,
                updatedAt = 0L,
                origin = MemoryOrigin.UserAdded,
            )
        )
        val incoming = listOf(
            MemoryMarkdownImporter.Item("已经在的", MemoryCategory.AboutYou, MemoryOrigin.UserAdded),
            MemoryMarkdownImporter.Item("新的", MemoryCategory.Hers, MemoryOrigin.HerRecorded),
            MemoryMarkdownImporter.Item("新的", MemoryCategory.AboutYou, MemoryOrigin.UserAdded),
        )
        val plan = MemoryMarkdownImporter.plan(existing, incoming)
        assertEquals(listOf("新的"), plan.map { it.content })
        assertEquals(MemoryCategory.Hers, plan.single().category)
        assertEquals(MemoryOrigin.HerRecorded, plan.single().origin)
    }
}
