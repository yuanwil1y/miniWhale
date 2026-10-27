package whaleui.nightsky.ltd.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import me.rerere.rikkahub.data.db.AppDatabase
import whaleui.nightsky.ltd.data.db.ScheduleTaskEntity
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * 定时任务仓库的 Room 实现，接口契约见 `_contracts/schedule-repository.md`。
 *
 * 顺序语义和记忆仓库一致：[ScheduleTaskEntity.sortIndex] 越大越靠前。
 * - [add]：新任务取「当前最大值 + 1」，排在最前；
 * - [restore]：按删除时的下标插回后整表重排 sort_index，严格回到原位置；
 * - [observeAll]：`ORDER BY sort_index DESC` 就是界面顺序。
 *
 * 除了接口方法，还给调度层（阶段 6 的 AlarmManager / Receiver）加了几个回写方法：
 * [setTodayPicked]（随机任务今天挑好的时间 + 日期）、[markSent]（上次发送时间）、
 * [todayPickedDate]（判断今天有没有挑过）、[getById]（Receiver 里按 id 取任务）。
 * `todayPickedDate` 模型里没有，只存在 `today_picked_date` 列里，[update] 不会把它抹掉。
 */
class RoomScheduleRepository(
    private val database: AppDatabase,
) : ScheduleRepository {

    private val dao get() = database.whaleScheduleDao()

    override fun observeAll(): Flow<List<ScheduleTask>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    override suspend fun add(task: ScheduleTask): ScheduleTask = database.withTransaction {
        val entity = task.toEntity(
            sortIndex = dao.maxSortIndex() + 1,
            todayPickedDate = null,
        )
        dao.upsert(entity)
        entity.toModel()
    }

    override suspend fun update(task: ScheduleTask): ScheduleTask? = database.withTransaction {
        val existing = dao.getById(task.id) ?: return@withTransaction null
        val entity = task.toEntity(
            sortIndex = existing.sortIndex,
            // 模型里没有 today_picked_date，整条更新时原样保留，避免把当天挑好的随机时间「丢日期」。
            todayPickedDate = existing.todayPickedDate,
        )
        dao.upsert(entity)
        entity.toModel()
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    override suspend fun restore(task: ScheduleTask, index: Int) {
        database.withTransaction {
            // 已经在列表里（比如连点两次撤销）就什么都不做。
            if (dao.getById(task.id) != null) return@withTransaction
            val ordered = dao.listAll().toMutableList()
            ordered.add(index.coerceIn(0, ordered.size), task.toEntity(sortIndex = 0, todayPickedDate = null))
            // 列表越靠前 sort_index 越大：size, size-1, ..., 1。
            val size = ordered.size
            dao.upsertAll(
                ordered.mapIndexed { position, entity ->
                    entity.copy(sortIndex = (size - position).toLong())
                }
            )
        }
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.updateEnabled(id, enabled)
    }

    // -----------------------------------------------------------------------
    // 调度器回写 / 读取（不在 ScheduleRepository 接口里）
    // -----------------------------------------------------------------------

    /** 按 id 取一条任务，Receiver 里用；不存在返回 null。 */
    suspend fun getById(id: String): ScheduleTask? = dao.getById(id)?.toModel()

    /** 随机任务今天挑好了时间：同时写 `today_picked_date` 和 `today_picked_time`。 */
    suspend fun setTodayPicked(id: String, pickedAt: LocalDateTime) {
        setTodayPicked(id, pickedAt.toLocalDate(), pickedAt.toLocalTime())
    }

    suspend fun setTodayPicked(id: String, date: LocalDate, time: LocalTime) {
        dao.updateTodayPicked(
            id = id,
            date = date.format(DATE_FORMATTER),
            time = time.format(TIME_FORMATTER),
        )
    }

    /** 发送成功（或静默也算跑过一轮）后回写上次发送时间。 */
    suspend fun markSent(id: String, sentAt: Long) {
        dao.updateLastSentAt(id, sentAt)
    }

    /** 调度器判断「今天有没有挑过」：`today_picked_date`；从没挑过返回 null。 */
    suspend fun todayPickedDate(id: String): LocalDate? =
        dao.todayPickedDate(id)?.let { value ->
            runCatching { LocalDate.parse(value, DATE_FORMATTER) }.getOrNull()
        }
}

// ---------------------------------------------------------------------------
// Entity ↔ 模型
// ---------------------------------------------------------------------------

private const val TIMING_FIXED = "fixed"
private const val TIMING_RANDOM = "random"
private const val REPEAT_DAILY = "daily"
private const val REPEAT_WEEKDAYS = "weekdays"
private const val REPEAT_CUSTOM = "custom"

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
private val DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)

private fun LocalTime.toDbTime(): String = format(TIME_FORMATTER)

private fun String.toLocalTimeOrNull(): LocalTime? =
    runCatching { LocalTime.parse(this, TIME_FORMATTER) }.getOrNull()

private fun ScheduleTask.toEntity(
    sortIndex: Long,
    todayPickedDate: String?,
): ScheduleTaskEntity {
    val timingParts = timing.toEntityParts()
    val repeatParts = repeat.toEntityParts()
    return ScheduleTaskEntity(
        id = id,
        title = title,
        hint = hint,
        timingType = timingParts.type,
        time = timingParts.time,
        windowStart = timingParts.windowStart,
        windowEnd = timingParts.windowEnd,
        repeatType = repeatParts.type,
        repeatDays = repeatParts.days,
        enabled = enabled,
        lastSentAt = lastSentAt,
        todayPickedDate = todayPickedDate,
        todayPickedTime = todayPickedTime?.toDbTime(),
        sortIndex = sortIndex,
    )
}

private data class TimingParts(
    val type: String,
    val time: String?,
    val windowStart: String?,
    val windowEnd: String?,
)

private fun ScheduleTiming.toEntityParts(): TimingParts = when (this) {
    is ScheduleTiming.Fixed -> TimingParts(
        type = TIMING_FIXED,
        time = time.toDbTime(),
        windowStart = null,
        windowEnd = null,
    )

    is ScheduleTiming.Random -> TimingParts(
        type = TIMING_RANDOM,
        time = null,
        windowStart = windowStart.toDbTime(),
        windowEnd = windowEnd.toDbTime(),
    )
}

private data class RepeatParts(val type: String, val days: String)

private fun ScheduleRepeat.toEntityParts(): RepeatParts = when (this) {
    ScheduleRepeat.Daily -> RepeatParts(REPEAT_DAILY, "")
    ScheduleRepeat.Weekdays -> RepeatParts(REPEAT_WEEKDAYS, "")
    is ScheduleRepeat.Custom -> RepeatParts(
        type = REPEAT_CUSTOM,
        days = days.sortedBy { it.value }.joinToString(",") { it.name },
    )
}

private fun ScheduleTaskEntity.toModel(): ScheduleTask = ScheduleTask(
    id = id,
    title = title,
    hint = hint,
    timing = toTiming(),
    repeat = toRepeat(),
    enabled = enabled,
    lastSentAt = lastSentAt,
    todayPickedTime = todayPickedTime?.toLocalTimeOrNull(),
)

private fun ScheduleTaskEntity.toTiming(): ScheduleTiming = when (timingType) {
    TIMING_RANDOM -> ScheduleTiming.Random(
        windowStart = windowStart?.toLocalTimeOrNull() ?: LocalTime.MIDNIGHT,
        windowEnd = windowEnd?.toLocalTimeOrNull() ?: LocalTime.MIDNIGHT,
    )

    // 认不出来的老值 / 坏数据回退成固定 00:00，别让一条坏行把整页拖垮。
    else -> ScheduleTiming.Fixed(time?.toLocalTimeOrNull() ?: LocalTime.MIDNIGHT)
}

private fun ScheduleTaskEntity.toRepeat(): ScheduleRepeat = when (repeatType) {
    REPEAT_WEEKDAYS -> ScheduleRepeat.Weekdays
    REPEAT_CUSTOM -> ScheduleRepeat.Custom(
        repeatDays.split(',')
            .mapNotNull { raw ->
                val name = raw.trim()
                if (name.isEmpty()) null else runCatching { DayOfWeek.valueOf(name) }.getOrNull()
            }
            .toSet(),
    )

    else -> ScheduleRepeat.Daily
}
