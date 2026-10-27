package whaleui.nightsky.ltd.alarm

import whaleui.nightsky.ltd.domain.settings.PermissionState
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 重排全部闹钟：定时任务 + 她自己定的一次性闹钟。
 *
 * 开机 / 时区变化 / 改时间 / 应用更新 / 精确闹钟权限变化（W-018）后都走这里：
 * 授权后以前退化成非精确的闹钟要换成精确的；没授权时登记的是非精确兜底（W-029）。
 */
class WhaleAlarmReschedule(
    private val alarmScheduler: WhaleAlarmScheduler,
    private val herAlarmScheduler: WhaleHerAlarmScheduler,
) {

    suspend fun all() {
        runCatchingCancellable { alarmScheduler.scheduleAll() }
        runCatchingCancellable { herAlarmScheduler.schedulePending() }
    }
}

/**
 * 盯着「精确闹钟」权限的状态：从系统设置页回来时状态变了（授权或撤销）就要重排（W-018）。
 * 第一次拿到状态只记下来，不算变化。
 */
class ExactAlarmPermissionWatcher {
    private var last: PermissionState? = null

    /** 记下最新状态；和上一次不一样时返回 true。 */
    fun changed(current: PermissionState?): Boolean {
        if (current == null) return false
        val previous = last
        last = current
        return previous != null && previous != current
    }
}
