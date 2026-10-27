package whaleui.nightsky.ltd.schedule

import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * 「定时任务什么时候触发」的纯计算。
 *
 * 这个对象不碰 AlarmManager / Receiver / 数据库，也不读系统时钟：所有「现在」都由参数传进来，
 * 所有「随机」都由调用方传 [Random]（测试里用固定种子），所以可以完全离线、确定性地测。
 *
 * 时间语义统一按「本地墙上时间」：
 * - [nextFireAt] 用 `now.zone` 把「某天的某个时刻」变成带时区的时刻；
 * - 窗口 / 免打扰 / 凌晨静默都只比较 [LocalTime]；
 * - `repeat` 每天 / 工作日 / 自定义星期几只筛选「哪一天」，不影响时刻。
 *
 * 设计约束（来自任务书 §10 6.3 / 6.4 / 6.6）：
 * - 开机补跑：迟到 ≤15 分钟照常触发，超过算「未发送」（[isLateBeyondGrace]）；
 * - 随机任务每天重新挑一次（[needsNewRandomPick] + [pickRandomTime]）；
 * - 凌晨 1:00–8:00、免打扰时段、总开关关闭都不触发主动消息（[shouldSkip]）。
 */
object ScheduleMath {

    /** 种子任务的闹钟说明在 assets 里的路径（相对 assets 根），见任务书 6.4。 */
    const val MORNING_PROMPT_PATH = "whale/prompts/alarm_morning.txt"
    const val NIGHT_PROMPT_PATH = "whale/prompts/alarm_night.txt"

    private const val MINUTES_PER_DAY = 24 * 60

    // -----------------------------------------------------------------------
    // 下次触发时间
    // -----------------------------------------------------------------------

    /**
     * 下一次触发时刻；没有下一次时返回 null。
     *
     * - [ScheduleTiming.Fixed]：今天（或之后）第一个满足 [ScheduleTask.repeat]、且严格晚于 [now] 的
     *   该时刻；正好等于 [now] 也算过了，顺延到下一个满足重复规则的日子。
     * - [ScheduleTiming.Random]：[randomPick] 是当天窗口里挑好的时刻（null 表示今天还没挑，
     *   这里返回 null —— 调用方要先用 [pickRandomTime] 挑一次并写回 `todayPickedTime`）。
     *   窗口跨零点时，落在 0 点之后的那一段算「窗口开始那天的下一次触发」，例如
     *   每天 23:00–01:00 的窗口挑到 00:30，触发日是窗口开始日的第二天。
     * - [ScheduleRepeat.Custom] 空集合永远不匹配，返回 null。
     */
    fun nextFireAt(
        task: ScheduleTask,
        now: ZonedDateTime,
        randomPick: LocalTime?,
    ): ZonedDateTime? = when (val timing = task.timing) {
        is ScheduleTiming.Fixed -> nextOccurrenceOf(timing.time, task.repeat, now)
        is ScheduleTiming.Random -> {
            val pick = randomPick ?: return null
            nextRandomOccurrenceOf(timing, pick, task.repeat, now)
        }
    }

    /**
     * 随机任务是否该重新挑时间：只在 [ScheduleTiming.Random] 上成立，
     * 且「上次挑的日期」不是今天就该挑（null 表示从没挑过）。
     */
    fun needsNewRandomPick(
        task: ScheduleTask,
        now: ZonedDateTime,
        lastPickedDate: LocalDate?,
    ): Boolean {
        if (task.timing !is ScheduleTiming.Random) return false
        return lastPickedDate != now.toLocalDate()
    }

    // -----------------------------------------------------------------------
    // 随机挑时
    // -----------------------------------------------------------------------

    /**
     * 在任务的窗口里均匀挑一个时刻（分钟精度）。
     *
     * 这个重载没有「现在」参数，因此 [minLeadMinutes] 是相对 **窗口起点** 的余量：
     * 保证挑出来的时刻至少比窗口起点晚这么多分钟（窗口比余量还短时退回窗口起点）。
     * 调用方如果已经知道「现在」，并且要保证相对现在也留出余量，用带 [now] 的重载。
     *
     * 窗口跨零点（`windowStart > windowEnd`）按 `[start, 24:00) ∪ [00:00, end)` 处理；
     * `windowStart == windowEnd` 当作一整天。固定时间的任务直接返回它自己的时刻。
     */
    fun pickRandomTime(
        task: ScheduleTask,
        random: Random,
        minLeadMinutes: Int = 2,
    ): LocalTime = when (val timing = task.timing) {
        is ScheduleTiming.Fixed -> timing.time
        is ScheduleTiming.Random -> {
            val (windowStart, windowEnd) = timing.minuteWindow()
            val earliest = windowStart + minLeadMinutes.coerceAtLeast(0)
            if (earliest >= windowEnd) {
                minuteOfDay(windowStart)
            } else {
                minuteOfDay(earliest + random.nextInt(windowEnd - earliest))
            }
        }
    }

    /**
     * 相对「现在」挑下一次窗口里的随机时刻：从昨天的窗口开始扫（跨零点窗口可能昨天开始、现在还没结束），
     * 找到第一个满足 repeat、且还能放下「[now] + [minLeadMinutes]」之后的时刻的窗口，在里面均匀取一分钟。
     *
     * 返回 null 表示没有下一次窗口（例如 [ScheduleRepeat.Custom] 空集合）。
     * 返回的只有时分，日期由 [nextFireAt] 用同一套 repeat 规则反推，两者是配套的。
     */
    fun pickRandomTime(
        task: ScheduleTask,
        now: ZonedDateTime,
        random: Random,
        minLeadMinutes: Int = 2,
    ): LocalTime? = when (val timing = task.timing) {
        is ScheduleTiming.Fixed -> timing.time
        is ScheduleTiming.Random -> {
            val (windowStart, windowEnd) = timing.minuteWindow()
            // 现在这一分钟已经过去了一部分的话，从下一分钟算起，避免挑到「已经过了」的时刻。
            val subMinute = if (now.second > 0 || now.nano > 0) 1 else 0
            val earliestNow = now.hour * 60 + now.minute +
                minLeadMinutes.coerceAtLeast(0) + subMinute
            val today = now.toLocalDate()
            var picked: LocalTime? = null
            // -1 是为了跨零点、昨天开始还没结束的窗口。
            for (offset in -1..7) {
                val windowDay = today.plusDays(offset.toLong())
                if (!task.repeat.matchesDay(windowDay.dayOfWeek)) continue
                val start = offset * MINUTES_PER_DAY + windowStart
                val end = offset * MINUTES_PER_DAY + windowEnd
                val earliest = maxOf(start, earliestNow)
                if (earliest >= end) continue
                picked = minuteOfDay(earliest + random.nextInt(end - earliest))
                break
            }
            picked
        }
    }

    // -----------------------------------------------------------------------
    // 免打扰 / 凌晨静默 / 汇总
    // -----------------------------------------------------------------------

    /**
     * [now] 是否落在免打扰时段里。支持跨零点（如 23:30–07:30）：
     * `start < end` 时是 `[start, end)`，`start > end` 时是 `[start, 24:00) ∪ [00:00, end)`。
     * [quietStart] == [quietEnd] 视为**不设**免打扰（返回 false）。
     */
    fun isQuietHour(now: LocalTime, quietStart: LocalTime, quietEnd: LocalTime): Boolean {
        if (quietStart == quietEnd) return false
        return if (quietStart < quietEnd) {
            now >= quietStart && now < quietEnd
        } else {
            now >= quietStart || now < quietEnd
        }
    }

    /** 凌晨 1:00（含）到 8:00（不含）：这段时间不触发任何主动消息。 */
    fun isNightSilence(now: LocalTime): Boolean =
        now >= NIGHT_SILENCE_START && now < NIGHT_SILENCE_END

    /**
     * 汇总「这条任务现在该不该跳过」，返回第一个命中的原因；都通过时返回 null。
     *
     * 优先级按任务书 6.6 的写法：总开关关闭 → 免打扰时段 → 凌晨 1–8 点 → 任务自己关了。
     * 也就是说凌晨 2 点既在免打扰里又是凌晨静默时，报 [SkipReason.QuietHours]。
     */
    fun shouldSkip(
        task: ScheduleTask,
        now: ZonedDateTime,
        settings: ScheduleSkipSettings,
    ): SkipReason? {
        if (!settings.proactiveEnabled) return SkipReason.GlobalDisabled
        val localTime = now.toLocalTime()
        if (isQuietHour(localTime, settings.quietStart, settings.quietEnd)) {
            return SkipReason.QuietHours
        }
        if (isNightSilence(localTime)) return SkipReason.NightSilence
        if (!task.enabled) return SkipReason.TaskDisabled
        return null
    }

    // -----------------------------------------------------------------------
    // 开机补跑
    // -----------------------------------------------------------------------

    /**
     * 迟到是否已经超过宽限：[now] 严格晚于 `scheduledAt + graceMinutes` 才返回 true。
     * 默认 15 分钟：14、15 分钟都照常触发，16 分钟算「未发送」。
     */
    fun isLateBeyondGrace(
        scheduledAt: ZonedDateTime,
        now: ZonedDateTime,
        graceMinutes: Int = 15,
    ): Boolean = now.toInstant()
        .isAfter(scheduledAt.toInstant().plusSeconds(graceMinutes.coerceAtLeast(0) * 60L))

    // -----------------------------------------------------------------------
    // 种子任务
    // -----------------------------------------------------------------------

    /**
     * 首次启动要创建的两个种子任务：每天 08:30「早上醒来」、每天 23:30「睡前」。
     *
     * [readPrompt] 由调用方注入（assets 读取，测试里直接读 `src/main/assets` 下的文件），
     * 入参是相对 assets 根的路径（[MORNING_PROMPT_PATH] / [NIGHT_PROMPT_PATH]），
     * 返回的文本去掉首尾空白后原样作为 `hint`，保证和 Hermes 的提示词逐字一致。
     */
    fun seedTasks(
        now: ZonedDateTime,
        readPrompt: (String) -> String,
    ): List<ScheduleTask> = listOf(
        ScheduleTask(
            id = SeedTaskIds.MORNING,
            title = "早上醒来",
            hint = readPrompt(MORNING_PROMPT_PATH).trim(),
            timing = ScheduleTiming.Fixed(LocalTime.of(8, 30)),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
            lastSentAt = null,
            todayPickedTime = null,
        ),
        ScheduleTask(
            id = SeedTaskIds.NIGHT,
            title = "睡前",
            hint = readPrompt(NIGHT_PROMPT_PATH).trim(),
            timing = ScheduleTiming.Fixed(LocalTime.of(23, 30)),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
            lastSentAt = null,
            todayPickedTime = null,
        ),
    )

    // -----------------------------------------------------------------------
    // 内部：触发时刻递推
    // -----------------------------------------------------------------------

    /** 从今天起最多扫 8 天，找第一个「重复规则匹配 + 严格晚于 now」的 [time]。 */
    private fun nextOccurrenceOf(
        time: LocalTime,
        repeat: ScheduleRepeat,
        now: ZonedDateTime,
    ): ZonedDateTime? {
        val today = now.toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (!repeat.matchesDay(date.dayOfWeek)) continue
            val candidate = ZonedDateTime.of(date, time, now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }

    private fun nextRandomOccurrenceOf(
        timing: ScheduleTiming.Random,
        pick: LocalTime,
        repeat: ScheduleRepeat,
        now: ZonedDateTime,
    ): ZonedDateTime? {
        val start = timing.windowStart
        val end = timing.windowEnd
        // 窗口起点 == 终点：按「整天」处理，等价于一个固定时刻。
        if (start == end) return nextOccurrenceOf(pick, repeat, now)
        // 不跨零点：窗口里的时刻就落在窗口开始那天，和固定时间的算法一样。
        if (start < end) return nextOccurrenceOf(pick, repeat, now)

        // 跨零点：窗口是 [start, 24:00) ∪ [00:00, end)。
        val beforeMidnight = pick >= start
        val afterMidnight = pick < end
        return when {
            beforeMidnight -> nextOccurrenceOf(pick, repeat, now)
            afterMidnight -> {
                // 0 点之后那一段：触发日是「窗口开始日」的下一天。
                // 从昨天开始扫，因为今天凌晨还在昨天的窗口里。
                val today = now.toLocalDate()
                for (offset in -1..7) {
                    val windowDay = today.plusDays(offset.toLong())
                    if (!repeat.matchesDay(windowDay.dayOfWeek)) continue
                    val fireDate = windowDay.plusDays(1)
                    val candidate = ZonedDateTime.of(fireDate, pick, now.zone)
                    if (candidate.isAfter(now)) return candidate
                }
                null
            }

            // pick 不在窗口里（调用方传错）：宽松地按固定时刻算，不抛异常。
            else -> nextOccurrenceOf(pick, repeat, now)
        }
    }

    // -----------------------------------------------------------------------
    // 内部：时间小工具
    // -----------------------------------------------------------------------

    /** 窗口在「分钟轴」上的区间 `[start, end)`；跨零点时 end 会大于 1440。 */
    private fun ScheduleTiming.Random.minuteWindow(): Pair<Int, Int> {
        val start = windowStart.hour * 60 + windowStart.minute
        val end = windowEnd.hour * 60 + windowEnd.minute
        return start to when {
            end > start -> end
            end < start -> end + MINUTES_PER_DAY
            else -> start + MINUTES_PER_DAY
        }
    }

    private fun minuteOfDay(minutes: Int): LocalTime =
        LocalTime.ofSecondOfDay(Math.floorMod(minutes, MINUTES_PER_DAY).toLong() * 60L)

    private fun ScheduleRepeat.matchesDay(day: DayOfWeek): Boolean = when (this) {
        ScheduleRepeat.Daily -> true
        ScheduleRepeat.Weekdays -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
        is ScheduleRepeat.Custom -> day in days
    }

    private val NIGHT_SILENCE_START: LocalTime = LocalTime.of(1, 0)
    private val NIGHT_SILENCE_END: LocalTime = LocalTime.of(8, 0)
}

/** 主动消息跳过原因。 */
enum class SkipReason {
    /** 设置页总开关关掉了。 */
    GlobalDisabled,

    /** 落在设置页的免打扰时段里。 */
    QuietHours,

    /** 凌晨 1:00–8:00 的全局静默。 */
    NightSilence,

    /** 这条任务自己的开关关掉了。 */
    TaskDisabled,
}

/** [ScheduleMath.shouldSkip] 需要的设置项（来自 `AppSettings`）。 */
data class ScheduleSkipSettings(
    val proactiveEnabled: Boolean,
    val quietStart: LocalTime,
    val quietEnd: LocalTime,
)
