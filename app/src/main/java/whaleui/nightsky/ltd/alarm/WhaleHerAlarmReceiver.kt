package whaleui.nightsky.ltd.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.rerere.rikkahub.AppScope
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 她自己定的**一次性**闹钟到点（或同一次触发的顺延 / 看门狗重试）时由系统回调（任务书 6.5）。
 *
 * 和 [WhaleAlarmReceiver] 一样不等这一轮生成：交给 [WhaleAlarmKeepAlive] 后立刻返回（W-007）。
 * 调度器实例通过 [KoinComponent] 从 Koin 取。
 */
class WhaleHerAlarmReceiver : BroadcastReceiver(), KoinComponent {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val note = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        val attempt = intent.getIntExtra(EXTRA_ATTEMPT, 0)
        val startedAtMillis = intent.getLongExtra(EXTRA_STARTED_AT, 0L)
        val app = context.applicationContext
        WhaleAlarmKeepAlive.launch(
            context = app,
            scope = get<AppScope>(),
            label = "her:$id#$attempt",
            fallback = { WhaleAlarmWorker.enqueueHerFire(app, id, note, attempt, startedAtMillis) },
        ) {
            get<WhaleHerAlarmScheduler>().handleFire(id, note, attempt, startedAtMillis)
        }
    }

    companion object {
        const val ACTION_FIRE = "whaleui.nightsky.ltd.alarm.action.HER_FIRE"
        const val EXTRA_ID = "herAlarmId"
        const val EXTRA_NOTE = "herAlarmNote"
        const val EXTRA_ATTEMPT = "herAlarmAttempt"
        const val EXTRA_STARTED_AT = "herAlarmStartedAt"

        fun fireIntent(
            context: Context,
            id: String,
            note: String,
            attempt: Int = 0,
            startedAtMillis: Long = 0L,
        ): Intent = Intent(context, WhaleHerAlarmReceiver::class.java).apply {
            action = ACTION_FIRE
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_NOTE, note)
            putExtra(EXTRA_ATTEMPT, attempt)
            putExtra(EXTRA_STARTED_AT, startedAtMillis)
        }
    }
}
