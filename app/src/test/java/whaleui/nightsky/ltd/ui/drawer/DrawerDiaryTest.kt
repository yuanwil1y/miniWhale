package whaleui.nightsky.ltd.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import whaleui.nightsky.ltd.domain.memory.DiaryEntry

class DrawerDiaryTest {

    private val today = LocalDate.of(2026, 10, 27)

    private fun entry(id: String, date: LocalDate, content: String) = DiaryEntry(id = id, date = date, weather = "", content = content)

    @Test
    fun `去掉段首时间和 Markdown 标记，行用空格接起来`() {
        val text = "# 标题\n\n[21:30] 文本一**加粗**。\n> 引用\n- 列表 *斜体*\n1. 有序 [链接](https://x.y)\n![图](a.png)\n---\n`代码`"
        assertEquals("标题 文本一加粗。 引用 列表 斜体 有序 链接 图 代码", diaryExcerpt(text))
    }

    @Test
    fun `过长时截断并加省略号`() {
        val text = "字".repeat(300)
        val out = diaryExcerpt(text)
        assertTrue(out.endsWith("…"))
        assertEquals(121, out.length)
    }

    @Test
    fun `取第一篇正文不为空的，日期今年不带年份`() {
        val diary = listOf(
            entry("a", today, "  \n[08:00]  "),
            entry("b", today.minusDays(1), "[22:10] 晚安"),
        )
        val preview = drawerDiaryPreview(diary, today)!!
        assertEquals("b", preview.entryId)
        assertEquals("10月26日", preview.dateLabel)
        assertEquals("晚安", preview.excerpt)
    }

    @Test
    fun `往年带年份，没有日记返回 null`() {
        assertEquals("2025年12月31日", diaryDateLabel(LocalDate.of(2025, 12, 31), today))
        assertNull(drawerDiaryPreview(emptyList(), today))
    }
}
