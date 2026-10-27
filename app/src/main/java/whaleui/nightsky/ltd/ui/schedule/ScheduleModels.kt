package whaleui.nightsky.ltd.ui.schedule

import whaleui.nightsky.ltd.schedule.SeedTaskIds

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask

// ---------------------------------------------------------------------------
// 数据模型
//
// 定时任务在 Telegram 里没有直接对应物（只有「定时发送一条消息」），
// 这里按需求定义；以后真实调度（WorkManager / AlarmManager）只换 Engine 实现。
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 时间线（由 ViewModel 从任务 + 当前时间推导，Repository 不负责）
// ---------------------------------------------------------------------------

enum class ScheduleStatus {
    /** 今天已发过 */
    Sent,

    /** 今天还没到时间 */
    Waiting,

    /** 时间过了但没发出去 */
    Missed,

    /** 开关关掉 */
    Closed,
}

@Immutable
data class ScheduleEntry(
    val task: ScheduleTask,
    val status: ScheduleStatus,
    /** 左侧时间列：具体 "HH:mm"，或随机的「白天某一刻」。 */
    val timeLabel: String,
    /** 排序用时间；未挑选的随机任务用窗口结束时间。 */
    val sortTime: LocalTime,
    /** 第二行括号里的类型文案：固定 / 随机。 */
    val kindLabel: String,
)

/**
 * 「今天的时间线」里她自己定的那些闹钟（只读，任务书 4.5）。
 *
 * 她的 `her_alarm` 是一次性的，和任务表不是一回事，但**要按时间排进同一条时间线、用同一种卡片**展示，
 * 否则会像 2026-10-26 截图里那样：别的任务都是「时间列 + 圆点 + 卡片」，她自己定的那条只有一行裸文字、还掉在 23:30 下面。
 */
@Immutable
data class HerAlarmRow(
    val id: String,
    val timeLabel: String,
    /** 排序 / 插「现在」线用。 */
    val sortTime: LocalTime,
    val notePreview: String,
    val sent: Boolean,
    /** 「接下来」分组里才有：明天 / 周五 / 下周二；今天的时间线里为 null。 */
    val dayLabel: String? = null,
)

/** 「接下来」分组的行：按触发时间正序，日子写成「明天 / 周五」，备注取前 20 字。 */
internal fun upcomingHerAlarmRows(
    alarms: List<whaleui.nightsky.ltd.data.HerAlarm>,
    now: Long,
    zone: java.time.ZoneId,
): List<HerAlarmRow> {
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return alarms.filter { !it.fired }.sortedBy { it.fireAt }.map { alarm ->
        val at = java.time.Instant.ofEpochMilli(alarm.fireAt).atZone(zone)
        val time = at.toLocalTime()
        HerAlarmRow(
            id = alarm.id,
            timeLabel = "%02d:%02d".format(time.hour, time.minute),
            sortTime = time,
            notePreview = alarm.note.trim().take(20),
            sent = false,
            dayLabel = whaleui.nightsky.ltd.data.HerAlarmDays.dayLabel(at.toLocalDate(), today),
        )
    }
}

/** 时间线里的一格：一个定时任务，或她自己定的一次性闹钟。 */
@Immutable
sealed interface TimelineSlot {
    val sortTime: LocalTime

    @Immutable
    data class Task(val entry: ScheduleEntry) : TimelineSlot {
        override val sortTime: LocalTime get() = entry.sortTime
    }

    @Immutable
    data class HerAlarm(val row: HerAlarmRow) : TimelineSlot {
        override val sortTime: LocalTime get() = row.sortTime
    }
}

/** 把任务和她自己定的闹钟按时间合成一条时间线；同一分钟时任务在前。 */
internal fun buildTimelineSlots(
    entries: List<ScheduleEntry>,
    alarms: List<HerAlarmRow>,
): List<TimelineSlot> = (entries.map { TimelineSlot.Task(it) } + alarms.map { TimelineSlot.HerAlarm(it) })
    .sortedWith(
        compareBy<TimelineSlot> { it.sortTime }
            .thenBy { if (it is TimelineSlot.Task) 0 else 1 },
    )

// ---------------------------------------------------------------------------
// 编辑弹层状态
// ---------------------------------------------------------------------------

enum class ScheduleTimingKind { Fixed, Random }

enum class ScheduleRepeatKind { Daily, Weekdays, Custom }

@Immutable
data class ScheduleEditor(
    /** null = 新建 */
    val taskId: String? = null,
    val title: String = "",
    val hint: String = "",
    val timingKind: ScheduleTimingKind = ScheduleTimingKind.Fixed,
    val hour: Int = 9,
    val minute: Int = 0,
    val windowStartHour: Int = 9,
    val windowStartMinute: Int = 0,
    val windowEndHour: Int = 11,
    val windowEndMinute: Int = 0,
    val repeatKind: ScheduleRepeatKind = ScheduleRepeatKind.Daily,
    val customDays: Set<DayOfWeek> = emptySet(),
) {
    val isNew: Boolean get() = taskId == null

    /**
     * 内置的两个种子任务（早上醒来 / 睡前）：提示词是整段系统提示（含 [SILENT] 这类标记），
     * 编辑页把它折进「高级」里（Q6，§4 #6）。只按任务 id 判断，不解析提示词内容。
     */
    val isBuiltIn: Boolean get() = taskId == SeedTaskIds.MORNING || taskId == SeedTaskIds.NIGHT

    val selectedTime: LocalTime get() = LocalTime.of(hour, minute)
    val windowStart: LocalTime get() = LocalTime.of(windowStartHour, windowStartMinute)
    val windowEnd: LocalTime get() = LocalTime.of(windowEndHour, windowEndMinute)

    /** 随机时段必须 end > start。 */
    val isWindowValid: Boolean get() = windowEnd > windowStart

    val canSave: Boolean
        get() = title.isNotBlank() &&
            (timingKind == ScheduleTimingKind.Fixed || isWindowValid) &&
            (repeatKind != ScheduleRepeatKind.Custom || customDays.isNotEmpty())
}

/** 菜单：只用编辑、删除两项。 */
@Immutable
data class ScheduleMenu(
    val taskId: String,
    val touch: Offset,
)

/** 撤销提示里待恢复的任务。 */
@Immutable
data class ScheduleUndo(
    val task: ScheduleTask,
    val index: Int,
)

/**
 * 整页状态。时间线由 ViewModel 推导后放在这里；界面只渲染。
 */
@Immutable
data class ScheduleUiState(
    val isLoading: Boolean = true,
    val tasks: List<ScheduleTask> = emptyList(),
    val timeline: List<ScheduleEntry> = emptyList(),
    /** 她自己定的闹钟（只读） */
    val herAlarms: List<HerAlarmRow> = emptyList(),
    /** 今天以后她定的闹钟（只读，时间线下面的「接下来」分组；空则不显示）。 */
    val upcoming: List<HerAlarmRow> = emptyList(),
    /**
     * 定时任务 + 她自己定的闹钟，按时间合并后的一整条时间线。
     * 界面照这个顺序渲染，她的闹钟和任务用同一种「时间列 + 圆点 + 卡片」。
     */
    val slots: List<TimelineSlot> = emptyList(),
    /** 当前时间（时分），每分钟刷新一次。 */
    val now: LocalTime = LocalTime.now(),
    /** 「现在」标记插在 [slots] 的哪个下标之前。 */
    val markerIndex: Int = 0,
    /** 「她今天的一天 · 周几 · 共 N 件」。 */
    val summary: String = "",
    val editor: ScheduleEditor? = null,
    val menu: ScheduleMenu? = null,
    val undo: ScheduleUndo? = null,
    /** 进页面后 +1，界面据此自动滚到「现在」附近。 */
    val scrollToNowTick: Int = 0,
    /** 有没有精确闹钟权限；没有时页面顶部提示一行（W-029）。 */
    val exactAlarmAllowed: Boolean = true,
) {
    fun menuTarget(): ScheduleTask? =
        menu?.let { target -> tasks.firstOrNull { it.id == target.taskId } }
}

// ---------------------------------------------------------------------------
// 文案 / 日期小工具
// ---------------------------------------------------------------------------

private val HourMinuteFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

fun formatTime(time: LocalTime): String = time.format(HourMinuteFormatter)

private val WeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

fun weekdayLabel(day: DayOfWeek): String = WeekdayLabels[day.value - 1]

internal fun ScheduleRepeat.matches(day: DayOfWeek): Boolean = when (this) {
    ScheduleRepeat.Daily -> true
    ScheduleRepeat.Weekdays -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
    is ScheduleRepeat.Custom -> day in days
}

/**
 * 随机时段 → 左侧时间列文案。窗口起点落在哪个时段就叫「XX某一刻」：
 * 0–4 凌晨 / 5–8 早上 / 9–11 上午 / 12–13 中午 / 14–17 下午 / 18–23 晚上。
 */
fun randomWindowLabel(windowStart: LocalTime): String {
    val prefix = when (windowStart.hour) {
        0, 1, 2, 3, 4 -> "凌晨"
        5, 6, 7, 8 -> "早上"
        9, 10, 11 -> "上午"
        12, 13 -> "中午"
        14, 15, 16, 17 -> "下午"
        else -> "晚上"
    }
    return prefix + "某一刻"
}

internal fun Long.toLocalDate(zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

internal fun LocalDateTime.toEpochMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
    atZone(zone).toInstant().toEpochMilli()

/** 当前分钟的整点时间：秒/纳秒归零。 */
internal fun LocalDateTime.truncatedToMinute(): LocalDateTime =
    truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
