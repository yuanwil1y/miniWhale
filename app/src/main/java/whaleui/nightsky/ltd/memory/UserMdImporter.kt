package whaleui.nightsky.ltd.memory

import java.util.UUID
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory
import whaleui.nightsky.ltd.domain.memory.MemoryOrigin

/**
 * USER.md 导入：按 Hermes 的规则用「单独一行的 `§`」分条，再只合并不覆盖地变成记忆。
 *
 * Hermes 的 USER.md 是「关于他」的事，条目之间用单独一行的 `§` 隔开（`README.md` 里写明
 * 只能合并、不能覆盖）。App 里把它拆成一条条 [Memory]（分类 [MemoryCategory.AboutYou]、
 * 来源 [MemoryOrigin.UserAdded]），原文件仍由 `PersonaStore` 保留。
 *
 * 纯函数：不读文件、不碰数据库、不联网。
 */
object UserMdImporter {

    /** 分条符：单独一行（行 trim 后恰好是它）才算分隔。 */
    const val SEPARATOR: String = "§"

    /**
     * 按「单独一行的 `§`」把 USER.md 拆成条目。
     *
     * - 每一条去掉首尾空白，条目内部的换行保留（USER.md 里一条可以写多行）；
     * - 空条目丢弃；
     * - 按原顺序去重：同内容（trim 后完全相等）只留第一次；
     * - 行内出现 `§`（例如 `一条 含§ 的事`）不算分隔；`§§` 这种也不是分隔。
     */
    fun parse(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        // 统一换行符，避免 CRLF 在条目内部留下 \r。
        val text = raw.replace("\r\n", "\n").replace('\r', '\n')

        val entries = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val buffer = StringBuilder()

        fun flush() {
            val entry = buffer.toString().trim()
            buffer.setLength(0)
            if (entry.isNotEmpty() && seen.add(entry)) entries.add(entry)
        }

        // 自己按 \n 切行，保证结尾没有换行也能正确处理。
        var start = 0
        for (index in text.indices) {
            if (text[index] != '\n') continue
            val line = text.substring(start, index)
            start = index + 1
            if (line.trim() == SEPARATOR) {
                flush()
            } else {
                buffer.append(line).append('\n')
            }
        }
        val tail = text.substring(start)
        if (tail.trim() == SEPARATOR) {
            flush()
        } else {
            buffer.append(tail)
        }
        flush()

        return entries
    }

    /**
     * 把 [incoming]（一般来自 [parse]）并进已有记忆：只合并不覆盖。
     *
     * - 已有同 content 的不重复加（比较时两边都 trim 后完全相等，区分大小写）；
     * - 已有的不删、不改，[existing] 只是用来查重；
     * - 新条目：`category = AboutYou`、`origin = UserAdded`、`createdAt = updatedAt = now`、
     *   id 用随机 UUID；
     * - 返回**只含需要新插入的记忆**的列表，按 [incoming] 里的原顺序（空条目与重复条目跳过）。
     *
     * 插入位置由调用方决定：仓库的「新增」语义是插到页面最前（见 `MemoryRepository.add`），
     * 所以逐条调用 `add()` 时新记忆会排在旧记忆前面。
     */
    fun merge(existing: List<Memory>, incoming: List<String>, now: Long): List<Memory> {
        if (incoming.isEmpty()) return emptyList()
        // 已有内容的 trim 快照；后面 add 成功时也会写回去，顺便把 incoming 内部重复也挡掉。
        val known = existing.mapTo(mutableSetOf()) { it.content.trim() }

        val added = mutableListOf<Memory>()
        for (raw in incoming) {
            val content = raw.trim()
            if (content.isEmpty()) continue
            if (!known.add(content)) continue
            added.add(
                Memory(
                    id = UUID.randomUUID().toString(),
                    content = content,
                    category = MemoryCategory.AboutYou,
                    createdAt = now,
                    updatedAt = now,
                    origin = MemoryOrigin.UserAdded,
                )
            )
        }
        return added
    }
}
