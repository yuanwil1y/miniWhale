package whaleui.nightsky.ltd.ui.schedule

import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming

/**
 * 「她今天的一天」时间线合并：她自己定的闹钟要按时间插进任务之间（同分钟任务在前）。
 * 2026-10-26 的 bug：her_alarm 只被追加在列表末尾、还是裸文字，23:10 掉在 23:30 下面。
 */
class ScheduleTimelineTest {

    @Test
    fun `upcoming rows name the day and keep the first 20 chars of the note`() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        // 2026-10-27 周二 21:00
        val now = java.time.LocalDateTime.of(2026, 10, 27, 21, 0).atZone(zone).toInstant().toEpochMilli()
        fun at(day: Int, hour: Int) = java.time.LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant().toEpochMilli()
        val alarms = listOf(
            whaleui.nightsky.ltd.data.HerAlarm("b", at(30, 10), "问问对方考试怎么样了，顺便说说今天看到的那只猫", 0L, false),
            whaleui.nightsky.ltd.data.HerAlarm("a", at(28, 9), "早点叫对方", 0L, false),
            whaleui.nightsky.ltd.data.HerAlarm("c", at(29, 9), "已经响过的", 0L, true),
        )

        val rows = upcomingHerAlarmRows(alarms, now, zone)

        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals(listOf("明天", "周五"), rows.map { it.dayLabel })
        assertEquals(listOf("09:00", "10:00"), rows.map { it.timeLabel })
        assertEquals("问问对方考试怎么样了，顺便说说今天看到的", rows[1].notePreview)
        assertEquals(20, rows[1].notePreview.length)
    }

    private fun taskEntry(id: String, time: LocalTime): ScheduleEntry = ScheduleEntry(
        task = ScheduleTask(
            id = id,
            title = id,
            hint = null,
            timing = ScheduleTiming.Fixed(time),
            repeat = ScheduleRepeat.Daily,
            enabled = true,
        ),
        status = ScheduleStatus.Waiting,
        timeLabel = formatTime(time),
        sortTime = time,
        kindLabel = "固定",
    )

    private fun herAlarm(id: String, time: LocalTime): HerAlarmRow = HerAlarmRow(
        id = id,
        timeLabel = formatTime(time),
        sortTime = time,
        notePreview = "今晚早点睡",
        sent = false,
    )

    private fun TimelineSlot.label(): String = when (this) {
        is TimelineSlot.Task -> entry.task.id
        is TimelineSlot.HerAlarm -> row.id
    }

    @Test
    fun `her alarm is merged into the timeline by its time`() {
        val slots = buildTimelineSlots(
            entries = listOf(taskEntry("sleep", LocalTime.of(23, 30))),
            alarms = listOf(herAlarm("her-early", LocalTime.of(23, 10))),
        )

        assertEquals(listOf("her-early", "sleep"), slots.map { it.label() })
    }

    @Test
    fun `task goes before her alarm when they share the same minute`() {
        val slots = buildTimelineSlots(
            entries = listOf(taskEntry("task-2310", LocalTime.of(23, 10))),
            alarms = listOf(herAlarm("her-2310", LocalTime.of(23, 10))),
        )

        assertEquals(listOf("task-2310", "her-2310"), slots.map { it.label() })
    }

    @Test
    fun `only her alarms are kept when there are no tasks today`() {
        val slots = buildTimelineSlots(
            entries = emptyList(),
            alarms = listOf(herAlarm("her-a", LocalTime.of(8, 0)), herAlarm("her-b", LocalTime.of(7, 0))),
        )

        assertEquals(listOf("her-b", "her-a"), slots.map { it.label() })
    }
}
