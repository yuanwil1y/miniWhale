package whaleui.nightsky.ltd.memory

import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/**
 * 把记忆拼成系统提示词里的一段（阶段 4 的 `WhaleSystemPrompt` 在 SOUL 之后接这一段）。
 *
 * 输出按分类固定三段，标题和 remember 的 category 取值一致（中性写法：用户 =「对方」）：
 *
 * ```
 * # 关于对方
 * - [m1a2b3c] 一条记忆（对方让记的）
 * - [m9f0e1d] 另一条记忆
 *
 * # 约定
 * - 一条约定
 *
 * # 你自己的
 * - 她自己的事
 * ```
 *
 * - 每条一行、形如 `- [m1a2b3c] 内容`（编号由 id 确定性推出，见 [MemoryTag]）；
 *   对方添加 / 导入的条目（[MemoryOrigin.UserAdded]）末尾带 [USER_ADDED_MARK]，这些不能改也不能删；
 *   分类内按 [Memory.updatedAt] 倒序（和记忆页「新的在前」一致）；
 * - 空分类整段省略（连标题一起）；一条都没有时返回空串，调用方据此不追加这一段；
 * - 总字数超过 [maxChars] 时：**优先保留「关于对方」**，其余分类的条目按 [Memory.updatedAt]
 *   倒序继续往里放，放不下就截断；字数按 Kotlin `String.length`（UTF-16 码元）计；
 * - 发生截断时通过 [log] 回调说明截断了多少条、最终多少字（不截断不调用）。
 *
 * 纯函数：不读时钟、不碰数据库、不联网。
 */
object WhaleMemoryPrompt {

    /** 默认字数上限，任务书 3.3 定的是 3000，可配置。 */
    const val DEFAULT_MAX_CHARS: Int = 3000

    /** 分类 → 提示词标题，顺序固定，不要改。 */
    private val Sections: List<Pair<MemoryCategory, String>> = listOf(
        MemoryCategory.AboutYou to ABOUT_THEM,
        MemoryCategory.Agreement to AGREEMENT,
        MemoryCategory.Hers to HERS,
    )

    /** 提示词标题，也是 remember / update_memory 的 category 取值（两处统一）。 */
    const val ABOUT_THEM: String = "关于对方"
    const val AGREEMENT: String = "约定"
    const val HERS: String = "你自己的"

    /** 对方添加 / 导入的记忆在提示词里的标记：带这个标记的不能改也不能删。 */
    const val USER_ADDED_MARK: String = "（对方让记的）"

    /** 分类 → 发给模型的名字（不是界面上的 [MemoryCategory.label]）。 */
    fun promptLabel(category: MemoryCategory): String = Sections.first { it.first == category }.second

    /** 模型给的 category → 分类；认不出来返回 null。兼容旧写法「关于他」「她的」「你们的约定」。 */
    fun parseCategory(raw: String?): MemoryCategory? = when (raw?.trim()) {
        ABOUT_THEM, "关于他" -> MemoryCategory.AboutYou
        AGREEMENT, "你们的约定" -> MemoryCategory.Agreement
        HERS, "她的" -> MemoryCategory.Hers
        else -> null
    }

    /**
     * @param memories 全部记忆（顺序不限，分类内会按 [Memory.updatedAt] 倒序重排）
     * @param maxChars 总字数上限，默认 [DEFAULT_MAX_CHARS]
     * @param log 截断日志回调，默认什么都不做
     */
    fun build(
        memories: List<Memory>,
        maxChars: Int = DEFAULT_MAX_CHARS,
        log: (String) -> Unit = {},
    ): String {
        // 每个分类内部按更新时间倒序；空白内容不占行。
        val bySections: List<List<Memory>> = Sections.map { (category, _) ->
            memories
                .filter { it.category == category && it.content.isNotBlank() }
                .sortedByDescending { it.updatedAt }
        }
        if (bySections.all { it.isEmpty() }) return ""

        val full = render(bySections)
        if (full.length <= maxChars) return full

        // 超上限：先按顺序放「关于对方」，再放其余分类（同样按 updatedAt 倒序）。
        val kept: List<MutableList<Memory>> = Sections.indices.map { mutableListOf() }
        val others = buildList {
            for (sectionIndex in 1 until bySections.size) {
                bySections[sectionIndex].forEach { add(sectionIndex to it) }
            }
        }.sortedByDescending { (_, memory) -> memory.updatedAt }
        val priority: List<Pair<Int, Memory>> = bySections[0].map { 0 to it } + others

        var truncated = 0
        for ((sectionIndex, memory) in priority) {
            val section = kept[sectionIndex]
            section.add(memory)
            if (render(kept).length <= maxChars) continue
            // 放不下就撤回，并记一条被截断（继续试后面的，等价于按 updatedAt 倒序取前缀）
            section.removeAt(section.lastIndex)
            truncated++
        }

        val text = render(kept)
        if (truncated > 0) {
            log("记忆提示词超过 ${maxChars} 字上限，截断 ${truncated} 条，最终 ${text.length} 字")
        }
        return text
    }

    /** 按固定分类顺序渲染；空分类整段省略，段与段之间空一行。 */
    private fun render(bySections: List<List<Memory>>): String = buildString {
        for ((index, memories) in bySections.withIndex()) {
            if (memories.isEmpty()) continue
            if (isNotEmpty()) append("\n\n")
            append("# ").append(Sections[index].second)
            for (memory in memories) {
                // 每条前面带短编号（稳定、由 id 推出），方便她 update_memory / forget_memory 时引用
                append('\n').append("- ").append(MemoryTag.labelOf(memory.id)).append(' ')
                    .append(memory.content.trim())
                if (memory.origin == MemoryOrigin.UserAdded) append(USER_ADDED_MARK)
            }
        }
    }
}
