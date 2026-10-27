package whaleui.nightsky.ltd.ui.schedule

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

// 定时任务编辑器的默认值（仓库 / 调度引擎接口在 domain.schedule）

/** 新建任务默认值：固定时间、下一个整点、每天。 */
fun nextWholeHour(now: LocalDateTime = LocalDateTime.now()): LocalTime =
    LocalTime.of(now.hour, 0).plusHours(1)

/** 编辑器的 id 生成。 */
fun newScheduleTaskId(): String = UUID.randomUUID().toString()
