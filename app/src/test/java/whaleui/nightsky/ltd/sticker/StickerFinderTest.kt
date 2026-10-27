package whaleui.nightsky.ltd.sticker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `find_sticker` 的过滤、排序、分页和排版（任务书 3.3 + 第四节）。 */
class StickerFinderTest {

    private fun pack(id: String, name: String = "系列 $id", sortOrder: Int = 0, enabled: Boolean = true) =
        StickerPack(
            id = id,
            name = name,
            titleOriginal = name,
            characters = listOf("角色甲"),
            animated = true,
            hasSoundOriginally = false,
            source = "测试",
            licenseNote = "仅个人使用",
            sortOrder = sortOrder,
            enabledForHer = enabled,
        )

    private fun sticker(
        id: String,
        packId: String = "01",
        description: String = "说明 $id",
        detail: String = "细节 $id",
        emotions: List<String> = listOf("开心"),
        couple: Boolean = false,
        textInImage: String = "",
        textMeaning: String = "",
        useWhen: String = "想他的时候",
        lastSentByHerAt: Long? = null,
        count: Int = 0,
    ) = Sticker(
        id = id,
        packId = packId,
        filePath = "stickers/$packId/$id.webp",
        thumbPath = "stickers/_thumbs/$id.png",
        description = description,
        detail = detail,
        textInImage = textInImage,
        textMeaning = textMeaning,
        emotions = emotions,
        couple = couple,
        useWhen = useWhen,
        lastSentByHerAt = lastSentByHerAt,
        sentByHerCount = count,
    )

    private fun candidate(item: Sticker, packName: String = "系列 ${item.packId}") =
        StickerWithPack(item, pack(item.packId, packName))

    private val now = 1_700_000_000_000L

    @Test
    fun `filters by emotion`() {
        val candidates = listOf(
            candidate(sticker("01-1", emotions = listOf("抱抱", "开心"))),
            candidate(sticker("01-2", emotions = listOf("委屈"))),
        )
        val result = StickerFinder.find(candidates, StickerFinder.Query(emotion = "抱抱"), now)
        assertTrue(result is StickerFinder.Outcome.Rows)
        val rows = (result as StickerFinder.Outcome.Rows).rows
        assertEquals(1, rows.size)
        assertTrue(rows.single().contains("01-1"))
    }

    @Test
    fun `filters by couple`() {
        val candidates = listOf(
            candidate(sticker("01-1", couple = true)),
            candidate(sticker("01-2", couple = false)),
        )
        val result = StickerFinder.find(candidates, StickerFinder.Query(couple = true), now)
        assertEquals(1, (result as StickerFinder.Outcome.Rows).rows.size)
    }

    @Test
    fun `sorts by keyword hit count`() {
        val candidates = listOf(
            candidate(sticker("01-1", detail = "围巾")),
            candidate(sticker("01-2", detail = "围巾 脸红 爱心")),
            candidate(sticker("01-3", detail = "围巾 脸红")),
        )
        val result = StickerFinder.find(
            candidates,
            StickerFinder.Query(keywords = listOf("围巾", "脸红")),
            now,
        ) as StickerFinder.Outcome.Rows
        assertEquals(listOf("01-2", "01-3", "01-1"), result.rows.map { it.substringBefore(" | ") })
    }

    @Test
    fun `unsent come before sent and older sends come before recent ones`() {
        val candidates = listOf(
            candidate(sticker("01-1", lastSentByHerAt = now - 1_000L)),
            candidate(sticker("01-2", lastSentByHerAt = now - 5L * 86_400_000L, count = 3)),
            candidate(sticker("01-3")),
        )
        val result = StickerFinder.find(candidates, StickerFinder.Query(), now) as StickerFinder.Outcome.Rows
        assertEquals(listOf("01-3", "01-2", "01-1"), result.rows.map { it.substringBefore(" | ") })
    }

    @Test
    fun `pages contain at most ten rows`() {
        val candidates = (1..12).map { candidate(sticker("01-$it")) }
        val first = StickerFinder.find(candidates, StickerFinder.Query(page = 1), now) as StickerFinder.Outcome.Rows
        val second = StickerFinder.find(candidates, StickerFinder.Query(page = 2), now) as StickerFinder.Outcome.Rows
        assertEquals(10, first.rows.size)
        assertEquals(2, second.rows.size)
        assertEquals(2, first.totalPages)
        assertTrue(first.hasMore)
        assertTrue(!second.hasMore)
    }

    @Test
    fun `empty result lists the emotions that actually have pictures`() {
        val candidates = listOf(
            candidate(sticker("01-1", emotions = listOf("抱抱", "开心"))),
            candidate(sticker("01-2", emotions = listOf("委屈"))),
        )
        val result = StickerFinder.find(candidates, StickerFinder.Query(emotion = "庆祝"), now)
        assertTrue(result is StickerFinder.Outcome.Empty)
        // 按 42 词表的顺序：开心 在 抱抱 前，委屈 在 抱抱 后
        assertEquals(listOf("开心", "抱抱", "委屈"), (result as StickerFinder.Outcome.Empty).availableEmotions)
    }

    @Test
    fun `row contains id pack detail text and last sent`() {
        val item = sticker(
            "01-1000001",
            description = "抱在一起",
            detail = "测试表情的详细说明",
            textInImage = "LOVE",
            textMeaning = "爱",
            useWhen = "测试场景",
            lastSentByHerAt = now - 3L * 86_400_000L,
        )
        val row = StickerFinder.formatRow(StickerFinder.Hit(candidate(item, "冬季约会"), 0), now)
        assertTrue(row.contains("01-1000001"))
        assertTrue(row.contains("冬季约会"))
        assertTrue(row.contains("测试表情的详细说明"))
        assertTrue(row.contains("图里的字:LOVE（爱）"))
        assertTrue(row.contains("适合:测试场景"))
        assertTrue(row.contains("上次发:3 天前发过"))
    }

    @Test
    fun `row omits the text segment when the sticker has no text`() {
        val row = StickerFinder.formatRow(StickerFinder.Hit(candidate(sticker("01-1", textInImage = "")), 0), now)
        assertTrue(!row.contains("图里的字"))
    }

    @Test
    fun `last sent text covers all buckets`() {
        assertEquals("没发过", StickerFinder.lastSentText(null, now))
        assertEquals("刚刚发过", StickerFinder.lastSentText(now - 5_000L, now))
        assertEquals("10 分钟前发过", StickerFinder.lastSentText(now - 10L * 60_000L, now))
        assertEquals("3 小时前发过", StickerFinder.lastSentText(now - 3L * 3_600_000L, now))
        assertEquals("2 天前发过", StickerFinder.lastSentText(now - 2L * 86_400_000L, now))
    }

    @Test
    fun `paging is clamped to the available range`() {
        val candidates = (1..3).map { candidate(sticker("01-$it")) }
        val result = StickerFinder.find(candidates, StickerFinder.Query(page = 99), now)
        assertEquals(1, (result as StickerFinder.Outcome.Rows).page)
    }
}
