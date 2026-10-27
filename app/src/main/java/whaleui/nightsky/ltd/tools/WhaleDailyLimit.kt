package whaleui.nightsky.ltd.tools

import java.time.LocalDate

/**
 * `search_web` / `read_webpage` 的每日调用上限（任务书 5.1：2026-11-01 起按次计费，
 * 搜索默认 100 次/天、读网页默认 50 次/天，可配置）。
 *
 * **计数会落盘**（任务书第六节 6-2）：默认用 [WhaleDailyLimitStore]（SharedPreferences），
 * 记录「日期 + 各工具已用次数」；换天自动清零。构造时可注入 [today] 和 [store]，
 * 测试里用固定日期 + 内存 store 就能验证跨天/重启行为。
 *
 * 线程安全：两个公开方法都是 `@Synchronized`，工具调用可能来自不同协程线程。
 */
class WhaleDailyLimit(
    private val searchLimit: Int = DEFAULT_SEARCH_LIMIT,
    private val fetchLimit: Int = DEFAULT_FETCH_LIMIT,
    private val today: () -> String = { LocalDate.now().toString() },
    private val store: Store? = null,
) {
    /** 存盘接口。默认实现见 [WhaleDailyLimitStore]；测试里用内存实现。 */
    interface Store {
        fun loadDay(): String?
        fun loadCounts(): Map<String, Int>
        fun save(day: String, counts: Map<String, Int>)
    }

    private var currentDay: String? = null
    private val counts = mutableMapOf<String, Int>()

    init {
        // 启动时恢复：只有存盘里的日期 == 今天才算数，否则丢掉（换天清零）
        val savedDay = store?.loadDay()
        if (savedDay == today()) {
            currentDay = savedDay
            counts.putAll(store.loadCounts())
        }
    }

    /**
     * 记一次调用。返回 `true` 表示这次还能调（计数 +1）；
     * 返回 `false` 表示今天这个工具的次数已经用完，调用方应直接返回中文提示、不要再请求网络。
     */
    @Synchronized
    fun tryConsume(tool: String): Boolean {
        rolloverIfNeeded()
        val limit = limitOf(tool)
        val used = counts[tool] ?: 0
        if (used >= limit) return false
        counts[tool] = used + 1
        persist()
        return true
    }

    /** 今天这个工具还剩多少次（不会小于 0）。 */
    @Synchronized
    fun remaining(tool: String): Int {
        rolloverIfNeeded()
        return (limitOf(tool) - (counts[tool] ?: 0)).coerceAtLeast(0)
    }

    private fun rolloverIfNeeded() {
        val day = today()
        if (day != currentDay) {
            currentDay = day
            counts.clear()
            persist()
        }
    }

    private fun persist() {
        val day = currentDay ?: return
        runCatching { store?.save(day, counts.toMap()) }
    }

    private fun limitOf(tool: String): Int = when (tool) {
        TOOL_READ_WEBPAGE -> fetchLimit
        TOOL_SEARCH_WEB -> searchLimit
        else -> searchLimit
    }

    companion object {
        const val TOOL_SEARCH_WEB: String = "search_web"
        const val TOOL_READ_WEBPAGE: String = "read_webpage"

        const val DEFAULT_SEARCH_LIMIT: Int = 100
        const val DEFAULT_FETCH_LIMIT: Int = 50
    }
}
