package whaleui.nightsky.ltd.alarm

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import whaleui.nightsky.ltd.domain.settings.AppSettings
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.schedule.ScheduleMath
import whaleui.nightsky.ltd.schedule.ScheduleSkipSettings
import whaleui.nightsky.ltd.schedule.SkipReason
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming

/**
 * 定时任务的「精确闹钟」调度层。
 *
 * 职责边界：
 * - 什么时候该触发：**全部交给 [ScheduleMath]**（`nextFireAt` / `pickRandomTime` /
 *   `shouldSkip` / `isLateBeyondGrace`），这里不重写任何时间计算；
 * - 怎么触发：到点由 [WhaleAlarmReceiver] 收到广播，[handleFire] 负责迟到 / 免打扰 / 1–8 点判定，
 *   通过后调 [AlarmTurnRunner.runAlarmTurn]（具体开一轮在 ChatService 侧实现）；
 *   跑完回写 `lastSentAt` 并排下一次；静默（runner 返回 false）也排下一次；
 * - 开机 / 时区变化 / 改时间 / 应用更新后重排：由 [WhaleAlarmReceiver] 的 intent-filter 覆盖，
 *   收到后调 [scheduleAll]；
 * - 没网：不放弃，用 WorkManager 排一个带网络约束的一次性任务（[WhaleAlarmWorker]），有网再跑同一套判定。
 *
 * 可测性：真正调 [AlarmManager] 的那一行抽成构造参数 [alarmSetter]（形状是
 * `(triggerAtMillis, taskId) -> Unit`，Intent 由生产默认实现构造，这样 JVM 单测
 * 不需要 Android Context / Intent）；[alarmCanceller] / [isNetworkAvailable] / [enqueueNetworkWait]
 * / [clock] / [random] 同样可注入。单测里把 [context] 传 null 即可。
 *
 * 关于随机任务：`ScheduleTask` 模型里只有 `todayPickedTime`、没有「哪一天挑的」字段，
 * 所以本类在内存里记 [pickedDays]（进程内有效），并以 `lastSentAt` 的日期兜底判断是否需要重挑，
 * 避免同一天被反复重挑导致时间漂移；进程重启后最多按兜底逻辑多挑一次。
 *
 * 她自己定的一次性闹钟（her_alarm）不走这里，由独立的调度器和 [WhaleHerAlarmReceiver] 处理。
 */
class WhaleAlarmScheduler(
    private val context: Context? = null,
    private val repository: ScheduleRepository,
    private val settings: SettingsRepository,
    private val runner: AlarmTurnRunner,
    private val clock: () -> ZonedDateTime = { ZonedDateTime.now() },
    private val random: Random = Random.Default,
    private val alarmSetter: (triggerAtMillis: Long, taskId: String) -> Unit =
        { triggerAtMillis, taskId -> setExactAlarm(context, triggerAtMillis, taskId) },
    private val alarmCanceller: (taskId: String) -> Unit = { taskId -> cancelAlarm(context, taskId) },
    private val isNetworkAvailable: () -> Boolean = { hasNetwork(context) },
    private val enqueueNetworkWait: (taskId: String, scheduledAtMillis: Long) -> Unit =
        { taskId, scheduledAtMillis ->
            context?.let { WhaleAlarmWorker.enqueue(it, taskId, scheduledAtMillis) }
        },
    /** 登记同一次触发的重试闹钟（遇忙顺延 / 看门狗，W-007 / W-023）；每个任务同时只有一个。 */
    private val retrySetter: (AlarmRetry) -> Unit = { retry -> setRetryAlarm(context, retry) },
    private val retryCanceller: (taskId: String) -> Unit = { taskId -> cancelRetryAlarm(context, taskId) },
) {

    /** 已注册过（本进程内）的任务 id，用于取消「任务被删除 / 关掉 / 没有下一次」的旧闹钟。 */
    private val scheduledIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 本进程内「随机任务今天已经挑过时间」的记录，见类注释。 */
    private val pickedDays: MutableMap<String, LocalDate> = ConcurrentHashMap()

    /** 今天发过之后、已经给「下一次」挑过时间的记录，避免反复写库。 */
    private val postSendPickDays: MutableMap<String, LocalDate> = ConcurrentHashMap()

    /** 本进程里正在跑闹钟轮的任务（看门狗到点时生成还没结束就别重开）。 */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** 每个任务最近处理过的触发时刻（同一次触发收到两遍时只处理一遍）。 */
    private val handledFires: MutableMap<String, Long> = ConcurrentHashMap()

    // -----------------------------------------------------------------------
    // 注册 / 取消
    // -----------------------------------------------------------------------

    /**
     * 重排全部闹钟：读库，给每个 enabled 的任务排下一次；
     * 随机任务该挑时间时用 [ScheduleMath.pickRandomTime] 挑好并写回 `todayPickedTime`；
     * 被关掉 / 没有下一次 / 已删除的任务会 [cancel] 掉旧闹钟。
     */
    suspend fun scheduleAll() {
        val now = clock()
        val tasks = repository.observeAll().first()
        val active = mutableSetOf<String>()
        for (task in tasks) {
            if (!task.enabled) {
                cancel(task.id)
                continue
            }
            val sentToday = isSentToday(task, now)
            val prepared = prepareForSchedule(task, now, sentToday)
            if (prepared == null) {
                cancel(task.id)
                continue
            }
            val base = scheduleBase(now, sentToday)
            val fireAt = ScheduleMath.nextFireAt(prepared, base, prepared.todayPickedTime)
            if (fireAt == null) {
                cancel(task.id)
                continue
            }
            schedule(prepared, fireAt)
            active += prepared.id
        }
        // 库里的任务被删掉后，本进程注册过的旧闹钟也要取消。
        for (vanished in (scheduledIds - active).toList()) {
            cancel(vanished)
        }
    }

    /** 把一条任务排到 [at]：RTC_WAKEUP 精确闹钟，触发时由 [WhaleAlarmReceiver] 处理。 */
    fun schedule(task: ScheduleTask, at: ZonedDateTime) {
        val triggerAtMillis = at.toInstant().toEpochMilli()
        scheduledIds += task.id
        alarmSetter(triggerAtMillis, task.id)
    }

    /** 取消一条任务的闹钟（幂等）。 */
    fun cancel(taskId: String) {
        scheduledIds -= taskId
        alarmCanceller(taskId)
    }

    /**
     * 开机 / 时区变化 / 改时间 / 应用更新后重排全部闹钟。
     * 四个 action 的注册由 [WhaleAlarmReceiver] 的 manifest intent-filter 负责，这里只做重排。
     */
    suspend fun registerBootReschedule() {
        scheduleAll()
    }

    // -----------------------------------------------------------------------
    // 触发
    // -----------------------------------------------------------------------

    /**
     * 闹钟到点（Receiver 调用）：判定迟到 / 总开关 / 免打扰 / 1–8 点，然后开一轮或等网。
     *
     * [attempt] > 0 表示这是同一次触发的重试（遇忙顺延或看门狗），[startedAtMillis] > 0 表示上一次
     * 已经开始生成（看门狗：进程可能在生成中途被杀了）。
     */
    suspend fun handleFire(
        taskId: String,
        scheduledAtMillis: Long,
        attempt: Int = 0,
        startedAtMillis: Long = 0L,
    ): FireOutcome = fire(taskId, scheduledAtMillis, requireNetwork = true, enforceGrace = true, attempt, startedAtMillis)
        .also { logOutcome("fire", taskId, attempt, it) }

    /**
     * [WhaleAlarmWorker] 在「已有网络」后调用：同样的判定，但不再检查网络、不再排 WorkManager；
     * 也**不再按 15 分钟宽限判迟到**——闹钟本身是准点响的，晚只是因为在等网（任务书 6.3：
     * 没网时用 WorkManager 等网络恢复后再跑），所以网络回来就补上这一轮。
     */
    suspend fun runAlarmTurnWhenOnline(taskId: String, scheduledAtMillis: Long): FireOutcome =
        fire(taskId, scheduledAtMillis, requireNetwork = false, enforceGrace = false, attempt = 0, startedAtMillis = 0L)
            .also { logOutcome("online", taskId, 0, it) }

    /** 每次到点的处理结果打一行（只有任务 id 和结果，没有内容）；JVM 单测里 Log 没有实现，失败不影响调度 */
    private fun logOutcome(path: String, taskId: String, attempt: Int, outcome: FireOutcome) {
        runCatching { android.util.Log.i("WhaleAlarmScheduler", "$path $taskId#$attempt -> $outcome") }
    }

    private suspend fun fire(
        taskId: String,
        scheduledAtMillis: Long,
        requireNetwork: Boolean,
        enforceGrace: Boolean,
        attempt: Int,
        startedAtMillis: Long,
    ): FireOutcome {
        val now = clock()
        val task = currentTask(taskId) ?: return FireOutcome.UnknownTask

        // 同一次触发收到两遍（非精确兜底时登记了两个闹钟）：只处理第一遍
        if (attempt == 0 && requireNetwork && handledFires.put(taskId, scheduledAtMillis) == scheduledAtMillis) {
            return FireOutcome.Duplicate
        }
        if (!inFlight.add(taskId)) {
            // 这一轮还在跑（看门狗到点时生成还没结束）：过一会儿再来看
            if (attempt > 0) {
                retrySetter(AlarmRetry(taskId, scheduledAtMillis, attempt, startedAtMillis, millis(now) + AlarmRetryPolicy.WATCHDOG_MS))
            }
            return FireOutcome.InProgress
        }
        try {
            return fireLocked(task, now, scheduledAtMillis, requireNetwork, enforceGrace, attempt, startedAtMillis)
        } finally {
            inFlight.remove(taskId)
        }
    }

    private suspend fun fireLocked(
        task: ScheduleTask,
        now: ZonedDateTime,
        scheduledAtMillis: Long,
        requireNetwork: Boolean,
        enforceGrace: Boolean,
        attempt: Int,
        startedAtMillis: Long,
    ): FireOutcome {
        val scheduledAt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(scheduledAtMillis), now.zone)
        val isRetry = attempt > 0

        if (isRetry) {
            // 看门狗：上一次开始生成后进程没了，但她其实已经发出去了 → 只补记，不再发一遍
            if (startedAtMillis > 0 && runner.hasRepliedSince(startedAtMillis)) {
                retryCanceller(task.id)
                currentTask(task.id)?.let { repository.update(it.copy(lastSentAt = startedAtMillis)) }
                return FireOutcome.Sent
            }
            if (millis(now) - scheduledAtMillis > AlarmRetryPolicy.RETRY_WINDOW_MS) {
                retryCanceller(task.id)
                return FireOutcome.GaveUp
            }
        }

        // 6.3：迟到 15 分钟以内照常触发；超过就不跑，只排下一次（不改 lastSentAt，界面会显示「未发送」）。
        if (enforceGrace && !isRetry && ScheduleMath.isLateBeyondGrace(scheduledAt, now)) {
            scheduleNext(task, now)
            return FireOutcome.LateSkipped
        }

        // 6.6：总开关 / 免打扰时段 / 凌晨 1–8 点 / 任务自己关了。
        val skipReason = ScheduleMath.shouldSkip(task, now, skipSettings())
        if (skipReason != null) {
            if (isRetry) retryCanceller(task.id)
            if (skipReason == SkipReason.TaskDisabled) {
                cancel(task.id)
            } else if (!isRetry) {
                // 重试时下一次在第一次触发时已经排好了
                scheduleNext(task, now)
            }
            return FireOutcome.Skipped(skipReason)
        }

        // 没网：排一个带 CONNECTED 约束的 WorkManager，等有网再跑同一套逻辑。
        if (requireNetwork && !isNetworkAvailable()) {
            if (isRetry) retryCanceller(task.id)
            enqueueNetworkWait(task.id, scheduledAtMillis)
            return FireOutcome.WaitingForNetwork
        }

        return dispatch(task, now, scheduledAtMillis, attempt)
    }

    private suspend fun dispatch(
        task: ScheduleTask,
        now: ZonedDateTime,
        scheduledAtMillis: Long,
        attempt: Int,
    ): FireOutcome {
        // W-007：先排好下一次，再开这一轮——生成中途进程被杀也不会丢下一次
        armNextAfterHandled(task, now)
        val startedAt = millis(now)
        val canRetry = attempt + 1 < AlarmRetryPolicy.MAX_ATTEMPTS
        // 看门狗：这一轮迟迟没有结束（进程被杀）就过一会儿再来看，补记或重开
        if (canRetry) {
            retrySetter(AlarmRetry(task.id, scheduledAtMillis, attempt + 1, startedAt, startedAt + AlarmRetryPolicy.WATCHDOG_MS))
        }
        val result = try {
            runner.runAlarmTurn(task.hint.orEmpty(), task.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AlarmTurnOutcome.Failed
        }
        return when (result) {
            AlarmTurnOutcome.Sent -> {
                retryCanceller(task.id)
                currentTask(task.id)?.let { repository.update(it.copy(lastSentAt = startedAt)) }
                FireOutcome.Sent
            }

            // 静默也算这一轮处理完了（下一次已经排好）
            AlarmTurnOutcome.Silent -> {
                retryCanceller(task.id)
                FireOutcome.Silent
            }

            // W-023：遇忙 / 失败用闹钟顺延，可以多次，但有上限
            AlarmTurnOutcome.Busy, AlarmTurnOutcome.Failed -> if (canRetry) {
                retrySetter(AlarmRetry(task.id, scheduledAtMillis, attempt + 1, 0L, startedAt + AlarmRetryPolicy.POSTPONE_MS))
                if (result == AlarmTurnOutcome.Busy) FireOutcome.Postponed else FireOutcome.Failed
            } else {
                retryCanceller(task.id)
                if (result == AlarmTurnOutcome.Busy) FireOutcome.GaveUp else FireOutcome.Failed
            }
        }
    }

    /**
     * 这一次触发已经在处理：按「今天已经处理过」排下一次（和发出去之后的算法一样：从明天起算，
     * 随机任务给明天挑时间）。静默时也用这个结果，所以不会在当天再响一次。
     */
    private suspend fun armNextAfterHandled(task: ScheduleTask, now: ZonedDateTime) {
        val prepared = prepareForSchedule(task, now, sentToday = true)
        if (prepared == null) {
            cancel(task.id)
            return
        }
        val fireAt = ScheduleMath.nextFireAt(prepared, scheduleBase(now, sentToday = true), prepared.todayPickedTime)
        if (fireAt == null) cancel(task.id) else schedule(prepared, fireAt)
    }

    private fun millis(at: ZonedDateTime): Long = at.toInstant().toEpochMilli()

    // -----------------------------------------------------------------------
    // 随机任务挑时 / 下一次
    // -----------------------------------------------------------------------

    private suspend fun scheduleNext(task: ScheduleTask, now: ZonedDateTime) {
        val sentToday = isSentToday(task, now)
        val prepared = prepareForSchedule(task, now, sentToday)
        if (prepared == null) {
            cancel(task.id)
            return
        }
        val base = scheduleBase(now, sentToday)
        val fireAt = ScheduleMath.nextFireAt(prepared, base, prepared.todayPickedTime)
        if (fireAt == null) {
            cancel(task.id)
        } else {
            schedule(prepared, fireAt)
        }
    }

    /**
     * 随机任务在排闹钟前先保证 `todayPickedTime` 是「有效」的：
     * - 固定时间任务原样返回；
     * - 今天已经发过（`lastSentAt` 是今天）→ 给下一次挑一个（用 `now + 1 天` 作为挑时基准，
     *   避免同一天窗口还没结束时又挑到当天稍晚、导致一天发两条）；
     * - 否则用 [ScheduleMath.needsNewRandomPick] + [ScheduleMath.pickRandomTime] 当天挑一次并写回仓库。
     *
     * 返回 null 表示没有可用时间（随机任务没挑出来 / 重复规则永远不匹配）。
     */
    private suspend fun prepareForSchedule(
        task: ScheduleTask,
        now: ZonedDateTime,
        sentToday: Boolean,
    ): ScheduleTask? {
        if (task.timing !is ScheduleTiming.Random) return task
        val today = now.toLocalDate()

        if (sentToday) {
            if (postSendPickDays[task.id] == today) return task
            val pick = ScheduleMath.pickRandomTime(task, now.plusDays(1), random) ?: return null
            postSendPickDays[task.id] = today
            return persistPick(task, pick)
        }

        val lastPickedDate = pickedDays[task.id] ?: lastSentDate(task, now)
        if (!ScheduleMath.needsNewRandomPick(task, now, lastPickedDate)) {
            return if (task.todayPickedTime != null) task else null
        }
        val pick = ScheduleMath.pickRandomTime(task, now, random) ?: return null
        pickedDays[task.id] = today
        return persistPick(task, pick)
    }

    private suspend fun persistPick(task: ScheduleTask, pick: LocalTime): ScheduleTask {
        if (task.todayPickedTime == pick) return task
        val updated = task.copy(todayPickedTime = pick)
        repository.update(updated)
        return updated
    }

    // -----------------------------------------------------------------------
    // 杂项
    // -----------------------------------------------------------------------

    private fun isSentToday(task: ScheduleTask, now: ZonedDateTime): Boolean =
        lastSentDate(task, now) == now.toLocalDate()

    private fun lastSentDate(task: ScheduleTask, now: ZonedDateTime): LocalDate? =
        task.lastSentAt?.let { Instant.ofEpochMilli(it).atZone(now.zone).toLocalDate() }

    /**
     * 计算「下一次」的起算点：今天已经发过的任务从明天 0 点起算，
     * 否则从 [now] 起算（`nextFireAt` 要求严格晚于起算点）。
     */
    private fun scheduleBase(now: ZonedDateTime, sentToday: Boolean): ZonedDateTime =
        if (sentToday) {
            now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        } else {
            now
        }

    private suspend fun currentTask(taskId: String): ScheduleTask? =
        repository.observeAll().first().firstOrNull { it.id == taskId }

    private suspend fun skipSettings(): ScheduleSkipSettings {
        val snapshot: AppSettings = settings.settings.first()
        return ScheduleSkipSettings(
            proactiveEnabled = snapshot.proactiveEnabled,
            quietStart = snapshot.quietStart,
            quietEnd = snapshot.quietEnd,
        )
    }

}

/** 一次触发的结果，供 Receiver / Worker / 单测断言。 */
sealed interface FireOutcome {
    /** runner 真的发了一条，`lastSentAt` 已回写。 */
    data object Sent : FireOutcome

    /** runner 判断这一轮静默，什么都没发；也算处理完成，排下一次。 */
    data object Silent : FireOutcome

    /** 迟到超过 15 分钟，不跑、只排下一次（界面显示「未发送」）。 */
    data object LateSkipped : FireOutcome

    /** 总开关 / 免打扰 / 1–8 点 / 任务关闭导致跳过。 */
    data class Skipped(val reason: SkipReason) : FireOutcome

    /** 没网：已排 WorkManager 等网，不发也不排下一次（Worker 会补上）。 */
    data object WaitingForNetwork : FireOutcome

    /** intent 里的 taskId 在库里找不到。 */
    data object UnknownTask : FireOutcome

    /** runner 失败（异常或生成失败）；不写 lastSentAt，下一次已排好，次数没用完时已登记重试。 */
    data object Failed : FireOutcome

    /** 他的消息正在回：已用闹钟登记顺延（W-023）。 */
    data object Postponed : FireOutcome

    /** 顺延 / 重试次数或时间用完，放弃这一次（下一次照常）。 */
    data object GaveUp : FireOutcome

    /** 同一次触发的第二个闹钟（非精确兜底的双保险），忽略。 */
    data object Duplicate : FireOutcome

    /** 这一轮还在跑（看门狗到点时生成没结束），已重新登记看门狗。 */
    data object InProgress : FireOutcome
}

/** 同一次触发的重试（遇忙顺延 / 看门狗）。 */
data class AlarmRetry(
    val taskId: String,
    val scheduledAtMillis: Long,
    val attempt: Int,
    /** 上一次开始生成的时刻；0 表示上一次没有开始生成（遇忙）。 */
    val startedAtMillis: Long,
    val triggerAtMillis: Long,
)

/** 顺延与重试的参数（定时任务与她的一次性闹钟共用）。 */
object AlarmRetryPolicy {
    /** 遇忙 / 失败后多久再试（任务书 6.1：顺延 2 分钟）。 */
    const val POSTPONE_MS: Long = 2 * 60_000L

    /** 看门狗：开始生成后这么久还没收尾，就当进程没了，回来看一眼。 */
    const val WATCHDOG_MS: Long = 10 * 60_000L

    /** 一次触发最多跑几次（第一次 + 5 次重试，约 10 分钟的顺延）。 */
    const val MAX_ATTEMPTS: Int = 6

    /** 离原定时刻超过这么久就不再重试。 */
    const val RETRY_WINDOW_MS: Long = 60 * 60_000L
}

// ---------------------------------------------------------------------------
// 生产实现：真正碰 AlarmManager / ConnectivityManager 的地方（单测都不会走到）
// ---------------------------------------------------------------------------

private fun setExactAlarm(
    context: Context?,
    triggerAtMillis: Long,
    taskId: String,
) {
    val ctx = requireNotNull(context) {
        "WhaleAlarmScheduler 需要在生产环境注入 context；单测请注入 alarmSetter"
    }
    // 闹钟的身份就是 taskId（WhaleAlarmRegistrar 把它编进 Intent 的 data，W-063）
    WhaleAlarmRegistrar.register(ctx, taskId, triggerAtMillis, WhaleAlarmReceiver.fireIntent(ctx, taskId, triggerAtMillis))
}

private fun cancelAlarm(context: Context?, taskId: String) {
    val ctx = context ?: return
    WhaleAlarmRegistrar.cancel(ctx, taskId, WhaleAlarmReceiver.fireIntent(ctx, taskId, 0L))
}

private fun retryKey(taskId: String): String = "retry:$taskId"

private fun setRetryAlarm(context: Context?, retry: AlarmRetry) {
    val ctx = context ?: return
    WhaleAlarmRegistrar.register(
        ctx,
        retryKey(retry.taskId),
        retry.triggerAtMillis,
        WhaleAlarmReceiver.fireIntent(ctx, retry.taskId, retry.scheduledAtMillis, retry.attempt, retry.startedAtMillis),
    )
}

private fun cancelRetryAlarm(context: Context?, taskId: String) {
    val ctx = context ?: return
    WhaleAlarmRegistrar.cancel(ctx, retryKey(taskId), WhaleAlarmReceiver.fireIntent(ctx, taskId, 0L))
}

private fun hasNetwork(context: Context?): Boolean {
    val ctx = context ?: return true
    return runCatching {
        val connectivityManager = ctx.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
        val network = connectivityManager.activeNetwork ?: return@runCatching false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(true)
}
