package whaleui.nightsky.ltd.sticker

/**
 * `find_sticker` 的查找/排序/排版逻辑（任务书 3.3）。
 *
 * **纯函数**，不碰数据库也不碰文件：调用方（`StickerTools` / `RoomStickerRepository`）
 * 先把「她能用 + 文件还在」的候选查出来传进来，这里只做过滤、排序和拼那一行文本。
 *
 * 排序规则（不随机）：
 * 1. 关键词命中数高的在前；
 * 2. 命中数相同：没发过的在前；
 * 3. 都发过：很久没发的在前；
 * 4. 再相同：按编号升序（保证同一份数据每次结果完全一样，前缀缓存稳定）。
 */
object StickerFinder {

    /** 每页最多 10 条。 */
    const val PAGE_SIZE: Int = 10

    data class Query(
        /** 42 词表里的情绪词；null 表示不按情绪过滤。 */
        val emotion: String? = null,
        /** 空格分隔后的关键词，简单包含匹配。 */
        val keywords: List<String> = emptyList(),
        /** 只要两个角色有亲密互动的。 */
        val couple: Boolean = false,
        /** 从 1 开始。 */
        val page: Int = 1,
    )

    data class Hit(
        val item: StickerWithPack,
        val matched: Int,
    )

    sealed interface Outcome {
        data class Rows(
            val rows: List<String>,
            val page: Int,
            val totalPages: Int,
            val total: Int,
            val hasMore: Boolean,
        ) : Outcome

        /** 什么都没找到：告诉模型现在实际有哪些情绪分类。 */
        data class Empty(val availableEmotions: List<String>) : Outcome
    }

    /** 现在实际「有图」的情绪分类（她能用 + 文件还在的候选里出现过的词），按词表顺序。 */
    fun availableEmotions(candidates: List<StickerWithPack>): List<String> =
        candidates.asSequence()
            .flatMap { it.sticker.emotions.asSequence() }
            .filter { StickerEmotions.isKnown(it) }
            .distinct()
            .sortedBy { StickerEmotions.orderOf(it) }
            .toList()

    /**
     * 查找一页。
     *
     * @param candidates 已经过滤过「她能用 + 文件存在」的候选
     * @param nowMs 当前时间（上次发送时间按它算「几天前」）
     */
    fun find(
        candidates: List<StickerWithPack>,
        query: Query,
        nowMs: Long,
    ): Outcome {
        val emotion = query.emotion?.trim()?.takeIf { it.isNotEmpty() }
        val keywords = query.keywords.map { it.trim() }.filter { it.isNotEmpty() }

        val filtered = candidates.asSequence()
            .filter { item ->
                emotion == null || item.sticker.emotions.any { it == emotion }
            }
            .filter { item -> !query.couple || item.sticker.couple }
            .map { item -> Hit(item = item, matched = countMatches(item.sticker, keywords)) }
            .toList()

        if (filtered.isEmpty()) {
            return Outcome.Empty(availableEmotions(candidates))
        }

        val sorted = filtered.sortedWith(
            compareByDescending<Hit> { it.matched }
                .thenBy { it.item.sticker.lastSentByHerAt != null }
                .thenBy { it.item.sticker.lastSentByHerAt ?: 0L }
                .thenBy { it.item.sticker.id },
        )

        val total = sorted.size
        val totalPages = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = query.page.coerceIn(1, totalPages)
        val from = (page - 1) * PAGE_SIZE
        val rows = sorted.drop(from).take(PAGE_SIZE).map { formatRow(it, nowMs) }
        return Outcome.Rows(
            rows = rows,
            page = page,
            totalPages = totalPages,
            total = total,
            hasMore = page < totalPages,
        )
    }

    /** 关键词命中数：每个关键词最多算 1 次（命中哪个字段都算）。 */
    fun countMatches(sticker: Sticker, keywords: List<String>): Int {
        if (keywords.isEmpty()) return 0
        val haystacks = listOf(
            sticker.description,
            sticker.detail,
            sticker.textInImage,
            sticker.textMeaning,
            sticker.useWhen,
        )
        return keywords.count { keyword ->
            haystacks.any { it.contains(keyword, ignoreCase = true) }
        }
    }

    /**
     * 每条返回一行文本：
     * `编号 | 系列名 | detail | 图里的字(没有就省略) | 适合:use_when | 上次发:…`
     */
    fun formatRow(hit: Hit, nowMs: Long): String {
        val sticker = hit.item.sticker
        val segments = mutableListOf(
            sticker.id,
            hit.item.pack.name,
            sticker.detail,
        )
        stickerTextSegment(sticker)?.let(segments::add)
        if (sticker.useWhen.isNotBlank()) segments += "适合:${sticker.useWhen}"
        segments += "上次发:${lastSentText(sticker.lastSentByHerAt, nowMs)}"
        return segments.joinToString(ROW_SEPARATOR)
    }

    /** 图里的字；有简体意思就一起带上（原文多为繁体/英文/日文）。 */
    private fun stickerTextSegment(sticker: Sticker): String? {
        if (sticker.textInImage.isBlank()) return null
        return if (sticker.textMeaning.isBlank()) {
            "图里的字:${sticker.textInImage}"
        } else {
            "图里的字:${sticker.textInImage}（${sticker.textMeaning}）"
        }
    }

    /** 「上次发」的文案：没发过 / 刚刚发过 / N 分钟前发过 / N 小时前发过 / N 天前发过。 */
    fun lastSentText(lastSentAt: Long?, nowMs: Long): String {
        if (lastSentAt == null || lastSentAt <= 0L) return "没发过"
        val deltaMs = (nowMs - lastSentAt).coerceAtLeast(0L)
        val minutes = deltaMs / 60_000L
        return when {
            minutes < 1L -> "刚刚发过"
            minutes < 60L -> "$minutes 分钟前发过"
            minutes < 60L * 24L -> "${minutes / 60L} 小时前发过"
            else -> "${minutes / (60L * 24L)} 天前发过"
        }
    }

    private const val ROW_SEPARATOR = " | "
}
