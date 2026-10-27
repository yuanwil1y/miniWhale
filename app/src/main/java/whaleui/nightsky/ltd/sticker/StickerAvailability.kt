package whaleui.nightsky.ltd.sticker

import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 「现在有没有能用的表情」的进程内快照（带 2 秒缓存）。
 *
 * 每轮请求里三处都要问这个问题：系统提示词（技能 + 情绪分类行）、每轮提醒、工具注册。
 * 一次请求里问三次没必要，所以这里缓存一小会儿；导入/删除之后最多 2 秒内就会被看到，
 * 用户感知不到。
 */
class StickerAvailability(
    private val repository: StickerRepository,
    private val ttlMs: Long = 2_000L,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** 有没有可用表情 + 现在实际有图的情绪分类。 */
    data class Status(
        val available: Boolean,
        val emotions: List<String>,
    ) {
        companion object {
            val NONE = Status(available = false, emotions = emptyList())
        }
    }

    @Volatile
    private var cachedAt: Long = Long.MIN_VALUE

    @Volatile
    private var cached: Status = Status.NONE

    suspend fun get(): Status {
        val timestamp = now()
        val current = cached
        if (timestamp - cachedAt in 0..ttlMs) return current
        return refresh()
    }

    /** 强制刷新（导入 / 删除之后调，界面操作立刻生效）。 */
    suspend fun refresh(): Status {
        val candidates = runCatchingCancellable { repository.usableStickers() }.getOrDefault(emptyList())
        val status = if (candidates.isEmpty()) {
            Status.NONE
        } else {
            Status(
                available = true,
                emotions = StickerFinder.availableEmotions(candidates),
            )
        }
        cached = status
        cachedAt = now()
        return status
    }

    fun invalidate() {
        cachedAt = Long.MIN_VALUE
    }
}
