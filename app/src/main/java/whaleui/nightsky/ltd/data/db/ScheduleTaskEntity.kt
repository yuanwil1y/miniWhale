package whaleui.nightsky.ltd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一条定时任务（`ui/schedule` 里的 [whaleui.nightsky.ltd.domain.schedule.ScheduleTask]）。
 *
 * `ScheduleTiming` / `ScheduleRepeat` 是 sealed 类型，落库用字符串还原：
 * - [timingType]：`"fixed"` / `"random"`；固定时间用 [time]（`HH:mm`），
 *   随机窗口用 [windowStart] / [windowEnd]（`HH:mm`，窗口可以跨零点）。
 * - [repeatType]：`"daily"` / `"weekdays"` / `"custom"`；[repeatDays] 存逗号分隔的
 *   `DayOfWeek.name`（如 `"MONDAY,WEDNESDAY"`），非 custom 时为空串。
 *
 * [todayPickedDate] 是模型里没有的字段（契约只要求 `todayPickedTime`）：调度器靠它判断
 * 「今天有没有挑过随机时间」，仓库读 [todayPickedTime] 时一起还原。格式 `yyyy-MM-dd`。
 *
 * 所有可空字段（`hint`、`time`、`windowStart`、`windowEnd`、`lastSentAt`、
 * `todayPickedDate`、`todayPickedTime`）都保持原样：写 null 读出来还是 null。
 *
 * [sortIndex] 越大越靠前（和 memory_item 一样）：新增取 max+1，撤销删除时整表重排。
 */
@Entity(tableName = "schedule_task")
data class ScheduleTaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val hint: String?,
    @ColumnInfo("timing_type")
    val timingType: String,
    /** 固定时间的 `HH:mm`；随机任务为 null。 */
    @ColumnInfo("time")
    val time: String?,
    /** 随机窗口起点 `HH:mm`；固定时间为 null。 */
    @ColumnInfo("window_start")
    val windowStart: String?,
    /** 随机窗口终点 `HH:mm`；固定时间为 null。 */
    @ColumnInfo("window_end")
    val windowEnd: String?,
    @ColumnInfo("repeat_type")
    val repeatType: String,
    /** 逗号分隔的 `DayOfWeek.name`，只有 custom 用；其它情况是空串。 */
    @ColumnInfo("repeat_days")
    val repeatDays: String,
    val enabled: Boolean,
    @ColumnInfo("last_sent_at")
    val lastSentAt: Long?,
    @ColumnInfo("today_picked_date")
    val todayPickedDate: String?,
    @ColumnInfo("today_picked_time")
    val todayPickedTime: String?,
    @ColumnInfo("sort_index")
    val sortIndex: Long,
)
