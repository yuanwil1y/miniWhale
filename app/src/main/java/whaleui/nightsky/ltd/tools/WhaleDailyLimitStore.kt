package whaleui.nightsky.ltd.tools

import android.content.Context

/**
 * [WhaleDailyLimit.Store] 的默认实现：SharedPreferences 存「日期 + 各工具已用次数」（任务书 6-2）。
 *
 * 只存三个非敏感数字，Key 绝不进来。`attach` 由 Bootstrap 调一次。
 */
class WhaleDailyLimitStore(context: Context) : WhaleDailyLimit.Store {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadDay(): String? = prefs.getString(KEY_DAY, null)

    override fun loadCounts(): Map<String, Int> = buildMap {
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(COUNT_PREFIX) && value is Int) {
                put(key.removePrefix(COUNT_PREFIX), value)
            }
        }
    }

    override fun save(day: String, counts: Map<String, Int>) {
        prefs.edit().apply {
            putString(KEY_DAY, day)
            // 清掉旧工具键，避免换工具后残留
            prefs.all.keys.filter { it.startsWith(COUNT_PREFIX) }.forEach { remove(it) }
            counts.forEach { (tool, used) -> putInt(COUNT_PREFIX + tool, used) }
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "whale_daily_limit"
        const val KEY_DAY = "day"
        const val COUNT_PREFIX = "count_"
    }
}