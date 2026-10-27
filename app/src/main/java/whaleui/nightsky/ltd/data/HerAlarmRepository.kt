package whaleui.nightsky.ltd.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import whaleui.nightsky.ltd.data.db.HerAlarmEntity
import whaleui.nightsky.ltd.data.db.WhaleHerAlarmDao
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * 她自己定的一次性闹钟（任务书 §10 6.5）。
 *
 * 数据模型故意和 `ScheduleTask` 分开：她的闹钟是「某个时刻说某件事」的一次性提醒，
 * 不是每天重复的任务；时间线里只读显示（[pendingToday]）。
 */
data class HerAlarm(
    val id: String,
    val fireAt: Long,
    val note: String,
    val createdAt: Long,
    val fired: Boolean,
)

/**
 * `her_alarm` 表的仓库。
 *
 * [now] 一律是 epoch 毫秒；「今天」按 [zone]（默认系统时区）算本地自然日，
 * 跨零点时 [pendingToday] 只返回今天 00:00–24:00 之间还没触发的那些。
 */
class HerAlarmRepository(
    private val dao: WhaleHerAlarmDao,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun observeAll(): Flow<List<HerAlarm>> =
        dao.observeAll().map { rows -> rows.map { it.toModel() } }

    /** 下一个还没触发、且不早于 [now] 的闹钟。 */
    suspend fun nextPending(now: Long): HerAlarm? = dao.nextPending(now)?.toModel()

    /** 新增一个闹钟。id / createdAt 由仓库生成，初始 `fired = false`。 */
    suspend fun add(fireAt: Long, note: String): HerAlarm {
        val alarm = HerAlarm(
            id = UUID.randomUUID().toString(),
            fireAt = fireAt,
            note = note,
            createdAt = System.currentTimeMillis(),
            fired = false,
        )
        dao.upsert(alarm.toEntity())
        return alarm
    }

    suspend fun markFired(id: String) {
        dao.markFired(id)
    }

    /** 今天（本地自然日）还没触发的闹钟，按触发时间正序；供定时任务页的时间线只读显示。 */
    suspend fun pendingToday(now: Long): List<HerAlarm> =
        pendingOn(Instant.ofEpochMilli(now).atZone(zone).toLocalDate())

    /** 某一天（本地自然日）还没触发的闹钟，按触发时间正序；set_alarm 按触发那天数「一天最多 5 个」。 */
    suspend fun pendingOn(date: LocalDate): List<HerAlarm> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.pendingBetween(start, end).map { it.toModel() }
    }

    /**
     * 今天以后（明天 00:00 起）还没触发的闹钟，按触发时间正序；定时任务页「接下来」分组只读显示。
     * set_alarm 最远只能定到 7 天后，这里多取一天余量。
     */
    suspend fun pendingAfterToday(now: Long): List<HerAlarm> {
        val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1)
        val start = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = tomorrow.plusDays(HerAlarmDays.MAX_DAYS_AHEAD.toLong() + 1).atStartOfDay(zone).toInstant().toEpochMilli()
        return dao.pendingBetween(start, end).map { it.toModel() }
    }
}

/** 她的闹钟「哪一天」的说法：set_alarm 的返回和定时任务页「接下来」分组共用。 */
object HerAlarmDays {
    /** set_alarm 的 date 最远能定到今天之后几天。 */
    const val MAX_DAYS_AHEAD: Int = 7

    private const val WEEKDAYS = "一二三四五六日"

    /** 今天 / 明天 / 周X；正好 7 天后（和今天同一个星期几）是「下周X」。 */
    fun dayLabel(date: LocalDate, today: LocalDate): String {
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, date)
        val weekday = "周" + WEEKDAYS[date.dayOfWeek.value - 1]
        return when {
            days == 0L -> "今天"
            days == 1L -> "明天"
            days >= 7L -> "下$weekday"
            else -> weekday
        }
    }

    /** 「明天 09:00」这样的一整句。 */
    fun label(fireAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(fireAt).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return dayLabel(at.toLocalDate(), today) + " " +
            at.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
    }
}

private fun HerAlarmEntity.toModel(): HerAlarm = HerAlarm(
    id = id,
    fireAt = fireAt,
    note = note,
    createdAt = createdAt,
    fired = fired,
)

private fun HerAlarm.toEntity(): HerAlarmEntity = HerAlarmEntity(
    id = id,
    fireAt = fireAt,
    note = note,
    createdAt = createdAt,
    fired = fired,
)
