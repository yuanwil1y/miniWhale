package whaleui.nightsky.ltd.memory

import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/**
 * 记忆导入：读 App 自己导出的 `memory/memories.md`（也就是 `MemoryRepository.exportAll()` 的格式）。
 *
 * 格式：
 * ```
 * # 记忆
 *
 * ## 关于你
 *
 * - 一条记忆（2026-10-25）
 *
 * ## 约定
 * ...
 * ## 她的
 * - 她自己记下的（2026-10-25） · 她记下的
 * ```
 *
 * 规则（和导出一一对应，保证跨设备能导回）：
 * - `## ` 后面是分类（关于你 / 约定 / 她的，也认枚举名 AboutYou / Agreement / Hers）；
 * - `- ` 开头的是一行一条；
 * - 行尾的 `（yyyy-MM-dd）` 是导出时附带的创建日期，导入时剥掉；
 * - 行尾的 ` · 她记下的` 表示来源是她记的（[MemoryOrigin.HerRecorded]），导入时剥掉标记。
 *
 * 纯函数：不读文件、不碰数据库。
 */
object MemoryMarkdownImporter {

    /** 一条待新增的记忆。 */
    data class Item(
        val content: String,
        val category: MemoryCategory,
        val origin: MemoryOrigin,
    )

    private val Heading = Regex("""^##\s+(.+?)\s*$""")
    private val Bullet = Regex("""^\s*-\s+(.*)$""")
    private val DateSuffix = Regex("""\s*[（(]\d{4}-\d{2}-\d{2}[）)]\s*$""")
    private val OriginSuffix = Regex("""\s*·\s*她记下的\s*$""")

    fun categoryOf(label: String): MemoryCategory? = when (label.trim()) {
        "关于你", MemoryCategory.AboutYou.name -> MemoryCategory.AboutYou
        "约定", MemoryCategory.Agreement.name -> MemoryCategory.Agreement
        "她的", MemoryCategory.Hers.name -> MemoryCategory.Hers
        else -> null
    }

    /**
     * 解析一份记忆 Markdown。
     *
     * 没有 `##` 分类头的条目按「关于你」处理；认不出的分类头下的条目同样按「关于你」，
     * 这样手写的文件不会因为多一行标题就整份丢掉。
     */
    fun parse(markdown: String): List<Item> {
        val text = markdown.replace("\r\n", "\n").replace('\r', '\n')
        val items = mutableListOf<Item>()
        var category: MemoryCategory? = null
        for (line in text.lineSequence()) {
            val heading = Heading.find(line.trim())
            if (heading != null) {
                category = categoryOf(heading.groupValues[1])
                continue
            }
            val bullet = Bullet.find(line) ?: continue
            var content = bullet.groupValues[1].trim()
            if (content.isEmpty()) continue
            val origin = if (OriginSuffix.containsMatchIn(content)) {
                content = OriginSuffix.replace(content, "")
                MemoryOrigin.HerRecorded
            } else {
                MemoryOrigin.UserAdded
            }
            content = DateSuffix.replace(content, "").trim()
            if (content.isEmpty()) continue
            items += Item(
                content = content,
                category = category ?: MemoryCategory.AboutYou,
                origin = origin,
            )
        }
        return items
    }

    /**
     * 和已有记忆比对，只返回需要新增的条目（按 [incoming] 顺序）。
     *
     * 比较用 trim 后的内容，跨分类去重：同一条内容已经在任何分类里就不再重复加。
     */
    fun plan(existing: List<Memory>, incoming: List<Item>): List<Item> {
        if (incoming.isEmpty()) return emptyList()
        val known = existing.mapTo(mutableSetOf()) { it.content.trim() }
        val result = mutableListOf<Item>()
        for (item in incoming) {
            val content = item.content.trim()
            if (content.isEmpty()) continue
            if (!known.add(content)) continue
            result += item.copy(content = content)
        }
        return result
    }
}
