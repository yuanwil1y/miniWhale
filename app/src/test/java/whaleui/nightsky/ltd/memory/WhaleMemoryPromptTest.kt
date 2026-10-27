package whaleui.nightsky.ltd.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/**
 * [WhaleMemoryPrompt] 的单元测试。
 *
 * 覆盖：三段标题与固定顺序、空分类省略、全空返回空串、分类内按 updatedAt 倒序、
 * 每条前面的稳定编号、上限截断（优先保「关于对方」、其余按 updatedAt 倒序、log 回调）。
 *
 * 所有测试文本都是编的假内容，与真实 USER.md / SOUL.md 无关。
 */
class WhaleMemoryPromptTest {

    private fun memory(
        content: String,
        category: MemoryCategory = MemoryCategory.AboutYou,
        updatedAt: Long = 0L,
        origin: MemoryOrigin = MemoryOrigin.HerRecorded,
    ): Memory = Memory(
        id = "fake-memory-$content",
        content = content,
        category = category,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        origin = origin,
    )

    /** 期望值里的编号：id 是测试自己编的 `fake-memory-<content>`。 */
    private fun tag(content: String): String = MemoryTag.labelOf("fake-memory-$content")

    @Test
    fun `memory tags are stable and derived from the id`() {
        assertEquals(MemoryTag.labelOf("abc"), MemoryTag.labelOf("abc"))
        assertTrue(MemoryTag.labelOf("abc") != MemoryTag.labelOf("abd"))
        assertTrue(Regex("^\\[m[0-9a-f]{6}]$").matches(MemoryTag.labelOf("abc")))
        assertEquals("abc123", MemoryTag.normalizeIdInput("[mAbC123]"))
    }

    @Test
    fun `three sections use the fixed titles and order`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("测试约定一", MemoryCategory.Agreement, 5),
                memory("测试用户住在测试城市", MemoryCategory.AboutYou, 1),
                memory("测试用的她的一条记忆", MemoryCategory.Hers, 9),
            )
        )

        val expected = "# 关于对方\n- ${tag("测试用户住在测试城市")} 测试用户住在测试城市\n\n" +
            "# 约定\n- ${tag("测试约定一")} 测试约定一\n\n" +
            "# 你自己的\n- ${tag("测试用的她的一条记忆")} 测试用的她的一条记忆"
        assertEquals(expected, text)
    }

    @Test
    fun `entries inside a section are sorted by updatedAt descending`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("旧的假记忆", MemoryCategory.AboutYou, 1),
                memory("新的假记忆", MemoryCategory.AboutYou, 100),
                memory("中间的假记忆", MemoryCategory.AboutYou, 50),
            )
        )

        val expected = "# 关于对方\n" +
            "- ${tag("新的假记忆")} 新的假记忆\n" +
            "- ${tag("中间的假记忆")} 中间的假记忆\n" +
            "- ${tag("旧的假记忆")} 旧的假记忆"
        assertEquals(expected, text)
    }

    @Test
    fun `user added memories carry the marker and hers do not`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("测试对方让记的事", MemoryCategory.AboutYou, 2, MemoryOrigin.UserAdded),
                memory("测试她自己记的事", MemoryCategory.AboutYou, 1, MemoryOrigin.HerRecorded),
            )
        )

        val expected = "# 关于对方\n" +
            "- ${tag("测试对方让记的事")} 测试对方让记的事（对方让记的）\n" +
            "- ${tag("测试她自己记的事")} 测试她自己记的事"
        assertEquals(expected, text)
    }

    @Test
    fun `prompt category names match the tool enum and parse old names`() {
        assertEquals("关于对方", WhaleMemoryPrompt.promptLabel(MemoryCategory.AboutYou))
        assertEquals("约定", WhaleMemoryPrompt.promptLabel(MemoryCategory.Agreement))
        assertEquals("你自己的", WhaleMemoryPrompt.promptLabel(MemoryCategory.Hers))
        assertEquals(MemoryCategory.AboutYou, WhaleMemoryPrompt.parseCategory("关于他"))
        assertEquals(MemoryCategory.Hers, WhaleMemoryPrompt.parseCategory("她的"))
        assertEquals(MemoryCategory.Agreement, WhaleMemoryPrompt.parseCategory("你们的约定"))
        assertEquals(null, WhaleMemoryPrompt.parseCategory("别的"))
    }

    @Test
    fun `empty categories are omitted entirely`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("测试用户喜欢测试用的食物", MemoryCategory.AboutYou, 1),
                memory("测试用的她有一条自己的假记忆", MemoryCategory.Hers, 2),
            )
        )

        assertFalse(text.contains("# 约定"))
        val expected = "# 关于对方\n- ${tag("测试用户喜欢测试用的食物")} 测试用户喜欢测试用的食物\n\n" +
            "# 你自己的\n- ${tag("测试用的她有一条自己的假记忆")} 测试用的她有一条自己的假记忆"
        assertEquals(expected, text)
    }

    @Test
    fun `no memories returns an empty string`() {
        assertEquals("", WhaleMemoryPrompt.build(emptyList()))
        assertEquals(
            "",
            WhaleMemoryPrompt.build(
                listOf(
                    memory("   ", MemoryCategory.AboutYou, 1),
                    memory("", MemoryCategory.Hers, 2),
                )
            ),
        )
    }

    @Test
    fun `blank content does not produce a bullet line`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("测试用户住在测试城市", MemoryCategory.AboutYou, 1),
                memory("   ", MemoryCategory.AboutYou, 2),
            )
        )

        assertEquals("# 关于对方\n- ${tag("测试用户住在测试城市")} 测试用户住在测试城市", text)
    }

    @Test
    fun `every entry is one bullet line`() {
        val text = WhaleMemoryPrompt.build(
            listOf(
                memory("假记忆一", MemoryCategory.AboutYou, 3),
                memory("假记忆二", MemoryCategory.AboutYou, 2),
                memory("假约定", MemoryCategory.Agreement, 1),
            )
        )

        val lines = text.lines()
        assertEquals("# 关于对方", lines[0])
        assertEquals("- ${tag("假记忆一")} 假记忆一", lines[1])
        assertEquals("- ${tag("假记忆二")} 假记忆二", lines[2])
        assertEquals("", lines[3])
        assertEquals("# 约定", lines[4])
        assertEquals("- ${tag("假约定")} 假约定", lines[5])
    }

    @Test
    fun `about him is kept before newer memories of other categories`() {
        val about = memory("测试用户住在测试城市", MemoryCategory.AboutYou, 1)
        val newerHers = memory("测试用的她的一条记忆", MemoryCategory.Hers, 100)
        val aboutOnly = WhaleMemoryPrompt.build(listOf(about))
        val logs = mutableListOf<String>()

        val text = WhaleMemoryPrompt.build(listOf(about, newerHers), maxChars = aboutOnly.length) { logs.add(it) }

        assertEquals(aboutOnly, text)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("截断 1 条"))
        assertTrue(logs[0].contains("最终 ${aboutOnly.length} 字"))
    }

    @Test
    fun `remaining categories are truncated by updatedAt descending`() {
        val about = memory("测试用户住在测试城市", MemoryCategory.AboutYou, 1)
        val newHers = memory("测试用的她的一条记忆", MemoryCategory.Hers, 30)
        val midAgreement = memory("测试约定二", MemoryCategory.Agreement, 20)
        val oldAgreement = memory("测试约定三", MemoryCategory.Agreement, 10)
        val expected = WhaleMemoryPrompt.build(listOf(about, newHers))
        val logs = mutableListOf<String>()

        val text = WhaleMemoryPrompt.build(
            listOf(about, newHers, midAgreement, oldAgreement),
            maxChars = expected.length,
            log = { logs.add(it) },
        )

        assertEquals(expected, text)
        assertEquals(1, logs.size)
        assertTrue(logs[0].contains("截断 2 条"))
        assertFalse(text.contains("测试约定"))
    }

    @Test
    fun `about him is truncated by updatedAt descending only when it alone exceeds the limit`() {
        val newAbout = memory("新的假记忆", MemoryCategory.AboutYou, 20)
        val oldAbout = memory("旧的假记忆", MemoryCategory.AboutYou, 10)
        val logs = mutableListOf<String>()

        val text = WhaleMemoryPrompt.build(listOf(oldAbout, newAbout), maxChars = 40) { logs.add(it) }

        assertTrue(text.startsWith("# 关于对方\n- ${tag("新的假记忆")} 新的假记忆"))
        assertFalse(text.contains("旧的假记忆"))
        assertEquals(1, logs.size)
    }
}