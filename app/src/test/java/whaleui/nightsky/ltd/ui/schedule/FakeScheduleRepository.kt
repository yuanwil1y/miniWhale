package whaleui.nightsky.ltd.ui.schedule

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import whaleui.nightsky.ltd.domain.schedule.ScheduleEngine
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming

// 测试用的内存版仓库与空调度（R1 / W-043 从 main 挪到 test 源集）

/** 这一轮的空实现：不做任何调度。 */
class FakeScheduleEngine : ScheduleEngine {
    override fun reschedule(tasks: List<ScheduleTask>) = Unit
}

// ---------------------------------------------------------------------------
// 内存版假实现
// ---------------------------------------------------------------------------

/** 内存版仓库：新增放最前，删除按 id 过滤，撤销按原下标插回。 */
class FakeScheduleRepository(
    seed: List<ScheduleTask> = defaultScheduleSeeds(),
) : ScheduleRepository {

    private val state = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<ScheduleTask>> = state.asStateFlow()

    override suspend fun add(task: ScheduleTask): ScheduleTask {
        state.update { listOf(task) + it }
        return task
    }

    override suspend fun update(task: ScheduleTask): ScheduleTask? {
        var updated: ScheduleTask? = null
        state.update { list ->
            list.map { existing ->
                if (existing.id != task.id) {
                    existing
                } else {
                    task.also { updated = it }
                }
            }
        }
        return updated
    }

    override suspend fun delete(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun restore(task: ScheduleTask, index: Int) {
        state.update { list ->
            if (list.any { it.id == task.id }) {
                list
            } else {
                list.toMutableList().apply { add(index.coerceIn(0, size), task) }
            }
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        state.update { list ->
            list.map { task -> if (task.id == id) task.copy(enabled = enabled) else task }
        }
    }
}

/**
 * 演示用假数据。时间相对「现在」生成：
 * - 前面 4 条已发送（时间均匀落在今天已经过去的部分）；
 * - 后面 2 条固定任务等待中；
 * - 1 条随机任务，窗口在「现在」之后，保证打开时是等待中的随机任务；
 * - 另外 2 条仅周末的任务，今天（演示环境是周一）不出现在时间线里。
 */
fun defaultScheduleSeeds(now: LocalDateTime = LocalDateTime.now()): List<ScheduleTask> {
    val today = now.toLocalDate()
    val minutesNow = now.hour * 60 + now.minute
    val zone = ZoneId.systemDefault()

    fun sentAt(fraction: Double): LocalDateTime {
        val total = (minutesNow * fraction).toInt().coerceIn(1, maxOf(1, minutesNow - 1))
        return today.atTime(total / 60, total % 60)
    }

    fun waitingAt(offsetMinutes: Int): LocalTime {
        val total = (minutesNow + offsetMinutes).coerceIn(1, 23 * 60 + 55)
        return LocalTime.of(total / 60, total % 60)
    }

    fun randomWindow(): Pair<LocalTime, LocalTime> {
        val startTotal = (minutesNow + 40).coerceAtMost(23 * 60 + 20)
        val endTotal = (minutesNow + 200).coerceAtMost(23 * 60 + 59)
        return if (endTotal > startTotal) {
            LocalTime.of(startTotal / 60, startTotal % 60) to
                LocalTime.of(endTotal / 60, endTotal % 60)
        } else {
            LocalTime.of(22, 0) to LocalTime.of(23, 55)
        }
    }

    fun sentTask(id: String, title: String, hint: String, fraction: Double): ScheduleTask {
        val sentAtTime = sentAt(fraction)
        return ScheduleTask(
            id = id,
            title = title,
            hint = hint,
            timing = ScheduleTiming.Fixed(sentAtTime.toLocalTime()),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
            lastSentAt = sentAtTime.toEpochMillis(zone),
        )
    }

    val (windowStart, windowEnd) = randomWindow()

    return listOf(
        sentTask("schedule-seed-1", "任务1", "提示1", 0.2),
        sentTask("schedule-seed-2", "任务2", "提示2", 0.4),
        sentTask("schedule-seed-3", "任务3", "提示3", 0.6),
        sentTask("schedule-seed-4", "任务4", "提示4", 0.8),
        ScheduleTask(
            id = "schedule-seed-5",
            title = "任务5",
            hint = "提示5",
            timing = ScheduleTiming.Fixed(waitingAt(30)),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
        ),
        ScheduleTask(
            id = "schedule-seed-6",
            title = "任务6",
            hint = "提示6",
            timing = ScheduleTiming.Fixed(waitingAt(75)),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
        ),
        ScheduleTask(
            id = "schedule-seed-7",
            title = "任务7",
            hint = "提示7",
            timing = ScheduleTiming.Random(windowStart, windowEnd),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
        ),
        ScheduleTask(
            id = "schedule-seed-8",
            title = "任务8",
            hint = "提示8",
            timing = ScheduleTiming.Fixed(LocalTime.of(9, 30)),
            repeat = ScheduleRepeat.Custom(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
            enabled = true,
        ),
        ScheduleTask(
            id = "schedule-seed-9",
            title = "任务9",
            hint = "提示9",
            timing = ScheduleTiming.Random(LocalTime.of(19, 0), LocalTime.of(22, 0)),
            repeat = ScheduleRepeat.Custom(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)),
            enabled = true,
        ),
    )
}
