package whaleui.nightsky.ltd.alarm

import android.content.Context
import android.util.Log
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import whaleui.nightsky.ltd.data.HerAlarmRepository
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.schedule.ScheduleMath
import whaleui.nightsky.ltd.schedule.SkipReason

/** 她的一次性闹钟要登记的一次触发（首次或重试）。 */
data class HerAlarmFire(
    val id: String,
    val note: String,
    val triggerAtMillis: Long,
    val attempt: Int = 0,
    /** 上一次开始生成的时刻；0 表示上一次没有开始生成（首次 / 遇忙）。 */
    val startedAtMillis: Long = 0L,
)

/**
 * 她自己定的**一次性**闹钟的调度（任务书 6.5）。
 *
 * 和 `ScheduleTask` 那套（[WhaleAlarmScheduler]）分开实现，原因：
 * - 语义不同：`her_alarm` 是一次性的、定完就烧掉；任务表是重复规则；
 * - 触发后的动作也不同：一次性闹钟触发完要 `markFired`，不需要排下一次。
 *
 * 只有**真正开了一轮**（Sent 或 Silent）才 `markFired`（W-023）；遇忙 / 失败用闹钟顺延，
 * 次数或时间用完就放弃，但不标记已触发（定时任务页如实显示「未发送」）。开始生成前先登记看门狗，
 * 进程在生成中途被杀也不会永远停在未触发（W-007）。跳过（总开关 / 免打扰 / 1–8 点）仍然标记已触发。
 *
 * 调度层只负责「什么时候响」；响的时候仍然走同一个 [AlarmTurnRunner]，所以请求注入、分条、静默、通知全都复用同一套。
 */
class WhaleHerAlarmScheduler(
    private val context: Context?,
    private val herAlarms: HerAlarmRepository,
    private val settings: SettingsRepository,
    private val runner: AlarmTurnRunner,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val alarmSetter: (HerAlarmFire) -> Unit = { fire -> register(context, alarmKey(fire.id), fire) },
    private val alarmCanceller: (id: String) -> Unit = { id -> cancel(context, alarmKey(id), id) },
    private val retrySetter: (HerAlarmFire) -> Unit = { fire -> register(context, retryKey(fire.id), fire) },
    private val retryCanceller: (id: String) -> Unit = { id -> cancel(context, retryKey(id), id) },
) {

    /** 本进程里正在跑的闹钟（看门狗到点时生成还没结束就别重开）。 */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 把「还没触发的一次性闹钟」全部注册给 AlarmManager。幂等。 */
    suspend fun schedulePending() {
        val now = clock().toInstant().toEpochMilli()
        val pending = herAlarms.observeAll().first()
            .filter { !it.fired && it.fireAt > now }
        pending.forEach { alarm ->
            alarmSetter(HerAlarmFire(alarm.id, alarm.note, alarm.fireAt))
        }
        log("schedulePending: ${pending.size} 个待触发")
    }

    /** Receiver 到点回调：判免打扰 / 1–8 点 / 总开关，通过就开一轮，真正开了一轮才标记已触发。 */
    suspend fun handleFire(id: String, note: String, attempt: Int = 0, startedAtMillis: Long = 0L): FireOutcome {
        val alarm = herAlarms.observeAll().first().firstOrNull { it.id == id }
        if (alarm == null) {
            retryCanceller(id)
            return FireOutcome.UnknownTask
        }
        // 已经处理过（同一次触发的第二个闹钟，或重试到点前已经标记）
        if (alarm.fired) return FireOutcome.Duplicate
        val now = clock()
        val nowMillis = now.toInstant().toEpochMilli()
        if (!inFlight.add(id)) {
            if (attempt > 0) retrySetter(HerAlarmFire(id, note, nowMillis + AlarmRetryPolicy.WATCHDOG_MS, attempt, startedAtMillis))
            return FireOutcome.InProgress
        }
        try {
            // 首次触发：同一次触发的另一个闹钟（非精确兜底的双保险）不用再响了
            if (attempt == 0) alarmCanceller(id)
            return fire(id, note, alarm.fireAt, now, attempt, startedAtMillis)
        } finally {
            inFlight.remove(id)
        }
    }

    private suspend fun fire(
        id: String,
        note: String,
        fireAt: Long,
        now: ZonedDateTime,
        attempt: Int,
        startedAtMillis: Long,
    ): FireOutcome {
        val nowMillis = now.toInstant().toEpochMilli()
        if (attempt > 0) {
            // 看门狗：上一次开始生成后进程没了，但她其实已经发出去了 → 只补记
            if (startedAtMillis > 0 && runner.hasRepliedSince(startedAtMillis)) {
                retryCanceller(id)
                herAlarms.markFired(id)
                return FireOutcome.Sent
            }
            if (nowMillis - fireAt > AlarmRetryPolicy.RETRY_WINDOW_MS) {
                log("handleFire: 重试时间用完，放弃（保持未触发）")
                retryCanceller(id)
                return FireOutcome.GaveUp
            }
        }
        val appSettings = settings.settings.first()
        val skip = when {
            !appSettings.proactiveEnabled -> SkipReason.GlobalDisabled
            ScheduleMath.isNightSilence(now.toLocalTime()) -> SkipReason.NightSilence
            ScheduleMath.isQuietHour(now.toLocalTime(), appSettings.quietStart, appSettings.quietEnd) -> SkipReason.QuietHours
            else -> null
        }
        if (skip != null) {
            log("handleFire: 跳过 $skip")
            retryCanceller(id)
            herAlarms.markFired(id)
            return FireOutcome.Skipped(skip)
        }

        val canRetry = attempt + 1 < AlarmRetryPolicy.MAX_ATTEMPTS
        if (canRetry) {
            retrySetter(HerAlarmFire(id, note, nowMillis + AlarmRetryPolicy.WATCHDOG_MS, attempt + 1, nowMillis))
        }
        val result = try {
            runner.runAlarmTurn(note)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("handleFire: 开一轮失败 $e")
            AlarmTurnOutcome.Failed
        }
        return when (result) {
            AlarmTurnOutcome.Sent, AlarmTurnOutcome.Silent -> {
                retryCanceller(id)
                herAlarms.markFired(id)
                if (result == AlarmTurnOutcome.Sent) FireOutcome.Sent else FireOutcome.Silent
            }

            AlarmTurnOutcome.Busy, AlarmTurnOutcome.Failed -> if (canRetry) {
                retrySetter(HerAlarmFire(id, note, nowMillis + AlarmRetryPolicy.POSTPONE_MS, attempt + 1, 0L))
                log("handleFire: $result，${AlarmRetryPolicy.POSTPONE_MS / 1000}s 后重试（第 ${attempt + 1} 次）")
                if (result == AlarmTurnOutcome.Busy) FireOutcome.Postponed else FireOutcome.Failed
            } else {
                retryCanceller(id)
                log("handleFire: 重试次数用完，放弃（保持未触发）")
                FireOutcome.GaveUp
            }
        }
    }

    /** JVM 单测里 android.util.Log 没有实现，日志失败不能影响调度。 */
    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    companion object {
        private const val TAG = "WhaleHerAlarm"

        /** 闹钟的 key；以前的 requestCode 是它的 hashCode，WhaleAlarmRegistrar 会按旧规则顺手取消旧闹钟（W-063）。 */
        private fun alarmKey(id: String) = "her:$id"

        private fun retryKey(id: String) = "her-retry:$id"

        private fun register(context: Context?, key: String, fire: HerAlarmFire) {
            val ctx = context ?: return
            WhaleAlarmRegistrar.register(
                ctx,
                key,
                fire.triggerAtMillis,
                WhaleHerAlarmReceiver.fireIntent(ctx, fire.id, fire.note, fire.attempt, fire.startedAtMillis),
            )
        }

        private fun cancel(context: Context?, key: String, id: String) {
            val ctx = context ?: return
            WhaleAlarmRegistrar.cancel(ctx, key, WhaleHerAlarmReceiver.fireIntent(ctx, id, ""))
        }
    }
}
