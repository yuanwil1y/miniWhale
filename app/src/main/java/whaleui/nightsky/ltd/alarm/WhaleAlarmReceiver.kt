package whaleui.nightsky.ltd.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 调度层的广播入口：
 *
 * 1. 收到 `BOOT_COMPLETED` / `TIMEZONE_CHANGED` / `TIME_SET` / `MY_PACKAGE_REPLACED`：
 *    调 [WhaleAlarmScheduler.scheduleAll] 重排全部闹钟（开机、时区变化、手改时间、应用更新）；
 *    这一步只读写库，很短，用 `goAsync()` 跑完；
 * 2. 收到 `ACTION_FIRE`（定时任务到点，或同一次触发的顺延 / 看门狗重试）：
 *    **不等这一轮生成**，交给 [WhaleAlarmKeepAlive]（前台服务保活 + 应用级 scope，
 *    拉不起前台服务时交给 expedited WorkManager）后立刻返回（W-007）。
 *    迟到 / 免打扰 / 1–8 点判定、开一轮、回写 `lastSentAt`、排下一次都在 [WhaleAlarmScheduler.handleFire] 里。
 *
 * manifest 里声明为 `exported=false`：系统广播照常能收到，外部 App 发不进来。
 * 调度器实例通过 [KoinComponent] 从 Koin 取（Application.onCreate 一定先于 Receiver 运行，Koin 已经起来了）。
 */
class WhaleAlarmReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        when (val action = intent.action) {
            null -> return
            in RESCHEDULE_ACTIONS -> runShort {
                get<WhaleAlarmReschedule>().all()
            }

            ACTION_FIRE -> {
                val taskId = intent.getStringExtra(EXTRA_TASK_ID)
                    ?.takeIf { it.isNotEmpty() }
                    ?: return
                val scheduledAtMillis = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L)
                if (scheduledAtMillis <= 0L) return
                val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
                val startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
                val app = context.applicationContext
                WhaleAlarmKeepAlive.launch(
                    context = app,
                    scope = get<AppScope>(),
                    label = "task:$taskId#$attempt",
                    fallback = {
                        WhaleAlarmWorker.enqueueTaskFire(app, taskId, scheduledAtMillis, attempt, startedAtMillis)
                    },
                ) {
                    get<WhaleAlarmScheduler>().handleFire(taskId, scheduledAtMillis, attempt, startedAtMillis)
                }
            }

            else -> Unit
        }
    }

    /** 只用于很短的重排：goAsync + IO 协程，异常全部吞掉（开机广播崩了会连累系统启动）。 */
    private fun runShort(block: suspend () -> Unit) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                // 广播里不能崩：调度器的异常已经自己兜底，这里再兜一层。
            } finally {
                pending.finish()
                scope.cancel()
            }
        }
    }

    companion object {
        /** 精确闹钟的 action（显式 Intent 指定 [WhaleAlarmReceiver]，仍然带上方便调试）。 */
        const val ACTION_FIRE: String = "whaleui.nightsky.ltd.alarm.action.FIRE"

        const val EXTRA_TASK_ID: String = "whaleui.nightsky.ltd.alarm.extra.TASK_ID"
        const val EXTRA_SCHEDULED_AT: String = "whaleui.nightsky.ltd.alarm.extra.SCHEDULED_AT"
        const val EXTRA_ATTEMPT: String = "whaleui.nightsky.ltd.alarm.extra.ATTEMPT"
        const val EXTRA_STARTED_AT: String = "whaleui.nightsky.ltd.alarm.extra.STARTED_AT"

        /** 精确闹钟权限授予时系统发的广播（`AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`，API 31）。 */
        const val ACTION_EXACT_ALARM_PERMISSION_CHANGED: String =
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"

        /**
         * 这些系统广播都表示「系统时间 / 时区 / 进程环境 / 精确闹钟权限变了，闹钟要重排」。
         * 撤销精确闹钟权限时系统会直接结束本 App 并清掉精确闹钟，下次进程启动时会重排（W-018）。
         */
        val RESCHEDULE_ACTIONS: Set<String> = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            ACTION_EXACT_ALARM_PERMISSION_CHANGED,
        )

        fun fireIntent(
            context: Context,
            taskId: String,
            scheduledAtMillis: Long,
            attempt: Int = 0,
            startedAtMillis: Long = 0L,
        ): Intent = Intent(context, WhaleAlarmReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_SCHEDULED_AT, scheduledAtMillis)
            .putExtra(EXTRA_ATTEMPT, attempt)
            .putExtra(EXTRA_STARTED_AT, startedAtMillis)
    }
}
