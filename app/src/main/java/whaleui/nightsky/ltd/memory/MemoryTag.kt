package whaleui.nightsky.ltd.memory

/**
 * 记忆的**短编号**：提示词里每条记忆前面带一个 `[mxxxxxx]`，方便她在 `update_memory` /
 * `forget_memory` 里直接引用（任务书 4.1）。
 *
 * 要求「编号稳定、不能随顺序变化」，所以由条目 id 确定性推出（FNV-1a 32 位取 6 位十六进制）：
 * 同一条记忆无论排序怎么变，编号都一样；进程重启也不变。
 * 6 位十六进制约 1600 万个取值，几十条记忆的实际碰撞概率可以忽略。
 */
object MemoryTag {

    private const val FNV_OFFSET_BASIS: Int = -0x7ee3623b // 0x811c9dc5
    private const val FNV_PRIME: Int = 0x01000193

    /** 记忆 id → `[mxxxxxx]` 里的那串（不含方括号和前缀）。 */
    fun tagOf(memoryId: String): String {
        var hash = FNV_OFFSET_BASIS
        val text = "memory:" + memoryId
        for (ch in text) {
            hash = hash xor ch.code
            hash *= FNV_PRIME
        }
        // 取低 24 位，6 位十六进制，小写
        return (hash and 0xFFFFFF).toString(16).padStart(6, '0')
    }

    /** 提示词里显示的样子：`[m1a2b3c]`。 */
    fun labelOf(memoryId: String): String = "[m${tagOf(memoryId)}]"

    /** 把模型给的编号（`m1a2b3c` / `[m1a2b3c]` / 原始 id）统一成 tag；认不出来返回 null。 */
    fun normalizeIdInput(raw: String): String? {
        val trimmed = raw.trim().removePrefix("[").removeSuffix("]").removePrefix("m")
        return trimmed.lowercase().takeIf { it.length == 6 && it.all { ch -> ch in "0123456789abcdef" } }
    }
}