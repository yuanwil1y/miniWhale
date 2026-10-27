package whaleui.nightsky.ltd.alarm

import me.rerere.rikkahub.AppScope
import android.util.Log
import kotlinx.coroutines.launch
import whaleui.nightsky.ltd.util.runCatchingCancellable
import whaleui.nightsky.ltd.domain.schedule.ScheduleEngine
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask

/**
 * `ScheduleEngine` 的真实实现：把「任务列表变了」翻译成「重新排一遍精确闹钟」。
 *
 * 界面（`ScheduleViewModel`）每次增删改任务、拨开关都会调 [reschedule]；
 * 这里不阻塞 UI 线程，直接丢到 `AppScope` 上跑 [WhaleAlarmScheduler.scheduleAll]。
 *
 * 之所以不直接把 `WhaleAlarmScheduler` 实现成 `ScheduleEngine`：一个是 suspend 的读库流程，
 * 一个是同步回调，中间需要这一层适配；也让调度层完全不依赖 UI 的接口。
 */
class WhaleScheduleEngine(
    private val scheduler: WhaleAlarmScheduler,
    private val appScope: AppScope,
) : ScheduleEngine {

    override fun reschedule(tasks: List<ScheduleTask>) {
        appScope.launch {
            // 失败至少留一行日志（W-052）；取消照常往外抛
            runCatchingCancellable { scheduler.scheduleAll() }
                .onFailure { runCatching { Log.w(TAG, "reschedule failed", it) } }
        }
    }

    private companion object {
        const val TAG = "WhaleScheduleEngine"
    }
}