package whaleui.nightsky.ltd.domain.schedule

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalTime

// 定时任务的领域模型（R1 / W-050 从 ui.schedule 搬来）

/** 时间类型：固定时间 / 随机时段（她自己在窗口里挑一个时间）。 */
@Immutable
sealed interface ScheduleTiming {

    @Immutable
    data class Fixed(val time: LocalTime) : ScheduleTiming

    @Immutable
    data class Random(
        val windowStart: LocalTime,
        val windowEnd: LocalTime,
    ) : ScheduleTiming
}

/** 重复规则：每天 / 工作日 / 自定义星期集合。 */
@Immutable
sealed interface ScheduleRepeat {

    data object Daily : ScheduleRepeat

    data object Weekdays : ScheduleRepeat

    @Immutable
    data class Custom(val days: Set<DayOfWeek>) : ScheduleRepeat
}

/** 一条定时任务。[lastSentAt] 与 [todayPickedTime] 由后端/调度写入。 */
@Immutable
data class ScheduleTask(
    val id: String,
    val title: String,
    /** 给她的提示，告诉她这条消息大概要说什么；可为空。 */
    val hint: String?,
    val timing: ScheduleTiming,
    val repeat: ScheduleRepeat,
    val enabled: Boolean,
    /** 上次发送时间，epoch 毫秒；null 表示从没发过。 */
    val lastSentAt: Long? = null,
    /** 随机任务今天挑好的时间；null 表示还没挑。只在随机任务上有意义。 */
    val todayPickedTime: LocalTime? = null,
)
