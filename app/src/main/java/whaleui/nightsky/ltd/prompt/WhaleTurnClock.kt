package whaleui.nightsky.ltd.prompt

import java.time.ZonedDateTime

/**
 * 每轮注入用的时钟。
 *
 * 注入的内容（报时、上一条消息的间隔）依赖「现在」，但纯函数不应该自己去读系统时间：
 * 生产接一个 [System]，测试接 `WhaleTurnClock { ZonedDateTime.of(...) }` 固定时钟，
 * 这样同一份输入永远得到同一份输出。
 *
 * 用法：`val now = clock.now()`，再把 `now` 传给 [WhaleGuard] 的各个函数。
 */
fun interface WhaleTurnClock {

    /** 当前时间（带设备时区）。 */
    fun now(): ZonedDateTime

    companion object {
        /** 生产默认实现：读系统时间与设备时区（Hermes 写死 +8，App 用本地时区）。 */
        val System: WhaleTurnClock = WhaleTurnClock { ZonedDateTime.now() }
    }
}
