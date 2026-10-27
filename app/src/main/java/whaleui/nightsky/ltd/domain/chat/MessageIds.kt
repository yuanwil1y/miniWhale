package whaleui.nightsky.ltd.domain.chat

import kotlin.uuid.Uuid

/**
 * 界面消息 id 的**确定性**推导（根治 `Key "msg-…" was already used` 崩溃）。
 *
 * 原来的做法是 `ConcurrentHashMap<Uuid, Long>` + 自增计数器（从 1000 起）：
 * 进程内每次启动都重新分配，`getOrPut` 也不是原子的，更关键的是——**同一个 `UIMessage.id`
 * 会被写进多个 `MessageNode`**（上一轮实测到 3 个节点共用一个消息 id），
 * 于是三条界面消息拿到同一个 Long，LazyColumn 的 key 直接冲突崩溃；界面层去重只是把崩溃
 * 掩盖成了「两个气泡的内容混在一起」。
 *
 * 现在改成：**id 由消息的 Uuid 确定性推出**，同一条消息永远得到同一个 id：
 * - 进程重启后不变（历史消息不会换 id）；
 * - 同一消息出现在多个节点时，界面层天然认为它是同一条（Updated 覆盖，而不是新增）；
 * - 不同消息几乎不可能撞：64 位 FNV-1a + 固定盐。
 *
 * 取值恒为**负数**：`ChatViewModel` 的乐观 id 从 10001 起是正数，这样两类 id 天然不会撞。
 */
object MessageIds {

    /**
     * FNV-1a 64 位常量。
     * 选它是因为实现只有几行、结果稳定（不依赖平台 hashCode），测试里可以直接钉死期望值。
     */
    private const val FNV_OFFSET_BASIS: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME: Long = 0x100000001b3L

    /** 加盐：将来别的模块也用同样的思路时，避免和这里撞。 */
    private const val SALT = "whale-msg:"

    /** `Uuid → 稳定的负数 Long`。纯函数，同输入必同输出。 */
    fun forUuid(uuid: Uuid): Long {
        var hash = FNV_OFFSET_BASIS
        val text = SALT + uuid.toString()
        for (ch in text) {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        // 恒为负：和聊天气泡里的乐观 id（正数，10001 起）天然分开，不会撞。
        // `or MIN_VALUE` 就是把符号位置 1；64 位哈希本身已经足够分散，
        // 「一个正 hash 和某个负 hash 置符号位后相等」的概率是 2^-63 级别，实际不会发生。
        return hash or Long.MIN_VALUE
    }

    /**
     * 一条消息拆成多个气泡时，除最后一个以外的气泡 id（最后一个用 [forUuid]，她的回复按它定位）。
     * [key] 是气泡在这条消息里的序号 + 附件 url，同一条消息里不重复。
     */
    fun forPart(uuid: Uuid, key: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (ch in "$SALT$uuid#$key") {
            hash = hash xor ch.code.toLong()
            hash *= FNV_PRIME
        }
        return hash or Long.MIN_VALUE
    }
}
