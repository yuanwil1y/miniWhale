package whaleui.nightsky.ltd.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * [ScheduleMath] 的单元测试。
 *
 * 全部时间都是用固定日期 + 固定时区 [ZONE] 构造的 [ZonedDateTime]，不读系统时钟；
 * 随机一律用固定种子的 [Random]。参考日历：2026-10-26 是周一，
 * 10-30 周五、10-31 周六、11-01 周日、11-02 周一。
 */
class ScheduleMathTest {

    // -----------------------------------------------------------------------
    // 固定时间 + 重复规则
    // -----------------------------------------------------------------------

    @Test
    fun `fixed daily fires today when the time is still ahead`() {
        val task = fixedTask(LocalTime.of(8, 30))
        assertEquals(at(day = 26, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 26, hour = 7), null))
    }

    @Test
    fun `fixed daily rolls to tomorrow when the time already passed`() {
        val task = fixedTask(LocalTime.of(8, 30))
        assertEquals(at(day = 27, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 26, hour = 9), null))
    }

    @Test
    fun `fixed daily is strictly after now`() {
        val task = fixedTask(LocalTime.of(8, 30))
        // 现在正好 08:30:00：今天这一秒已经算「到点」，下一次是明天 08:30。
        assertEquals(at(day = 27, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 26, hour = 8, minute = 30), null))
    }

    @Test
    fun `fixed weekdays skips the weekend`() {
        val task = fixedTask(LocalTime.of(8, 30), repeat = ScheduleRepeat.Weekdays)
        // 周五 09:00 已经错过 08:30，下一个工作日是下周一。
        assertEquals(at(day = 2, month = 11, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 30, hour = 9), null))
    }

    @Test
    fun `fixed weekdays on the weekend goes to monday`() {
        val task = fixedTask(LocalTime.of(8, 30), repeat = ScheduleRepeat.Weekdays)
        assertEquals(at(day = 2, month = 11, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 31, hour = 7), null))
    }

    @Test
    fun `fixed custom only fires on the listed days`() {
        val task = fixedTask(
            LocalTime.of(8, 30),
            repeat = ScheduleRepeat.Custom(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)),
        )
        // 周一 09:00 已过 → 周三 08:30。
        assertEquals(at(day = 28, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 26, hour = 9), null))
        // 周一 07:00 还没到 → 今天 08:30。
        assertEquals(at(day = 26, hour = 8, minute = 30), ScheduleMath.nextFireAt(task, at(day = 26, hour = 7), null))
    }

    @Test
    fun `custom with empty days has no next fire time`() {
        val task = fixedTask(LocalTime.of(8, 30), repeat = ScheduleRepeat.Custom(emptySet()))
        assertNull(ScheduleMath.nextFireAt(task, at(day = 26, hour = 7), null))
    }

    // -----------------------------------------------------------------------
    // 随机任务的触发时刻
    // -----------------------------------------------------------------------

    @Test
    fun `random daily uses the picked time`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertEquals(
            at(day = 26, hour = 20, minute = 15),
            ScheduleMath.nextFireAt(task, at(day = 26, hour = 18), LocalTime.of(20, 15)),
        )
    }

    @Test
    fun `random without a pick has no fire time`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertNull(ScheduleMath.nextFireAt(task, at(day = 26, hour = 18), null))
    }

    @Test
    fun `random window that already ended rolls to tomorrow`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertEquals(
            at(day = 27, hour = 20, minute = 15),
            ScheduleMath.nextFireAt(task, at(day = 26, hour = 21), LocalTime.of(20, 15)),
        )
    }

    @Test
    fun `random cross midnight pick after midnight fires on the next day`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0))
        assertEquals(
            at(day = 27, hour = 0, minute = 30),
            ScheduleMath.nextFireAt(task, at(day = 26, hour = 22), LocalTime.of(0, 30)),
        )
    }

    @Test
    fun `random cross midnight pick before midnight fires the same day`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0))
        assertEquals(
            at(day = 26, hour = 23, minute = 45),
            ScheduleMath.nextFireAt(task, at(day = 26, hour = 22), LocalTime.of(23, 45)),
        )
    }

    @Test
    fun `random cross midnight still sees the window that started yesterday`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0))
        // 周二 00:40 时，周一开始的窗口已经结束；下一次是周三 00:30。
        assertEquals(
            at(day = 28, hour = 0, minute = 30),
            ScheduleMath.nextFireAt(task, at(day = 27, hour = 0, minute = 40), LocalTime.of(0, 30)),
        )
    }

    @Test
    fun `random cross midnight weekday window can fire on saturday`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0), repeat = ScheduleRepeat.Weekdays)
        // 周五 23:10 的窗口挑到 00:30：周六凌晨触发，但归属周五的窗口。
        assertEquals(
            at(day = 31, hour = 0, minute = 30),
            ScheduleMath.nextFireAt(task, at(day = 30, hour = 23, minute = 10), LocalTime.of(0, 30)),
        )
    }

    @Test
    fun `random with empty custom days never fires`() {
        val task = randomTask(
            LocalTime.of(19, 0),
            LocalTime.of(22, 0),
            repeat = ScheduleRepeat.Custom(emptySet()),
        )
        assertNull(ScheduleMath.nextFireAt(task, at(day = 26, hour = 18), LocalTime.of(20, 15)))
    }

    @Test
    fun `random window with equal start and end behaves like a fixed time`() {
        val task = randomTask(LocalTime.of(9, 0), LocalTime.of(9, 0))
        assertEquals(
            at(day = 26, hour = 9, minute = 0),
            ScheduleMath.nextFireAt(task, at(day = 26, hour = 8), LocalTime.of(9, 0)),
        )
    }

    // -----------------------------------------------------------------------
    // 每天重新挑随机时间
    // -----------------------------------------------------------------------

    @Test
    fun `a random task that was never picked needs a pick`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertTrue(ScheduleMath.needsNewRandomPick(task, at(day = 26, hour = 0), null))
    }

    @Test
    fun `a random task does not need a second pick on the same day`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertFalse(
            ScheduleMath.needsNewRandomPick(
                task,
                at(day = 26, hour = 23, minute = 59),
                LocalDate.of(2026, 10, 26),
            )
        )
    }

    @Test
    fun `a random task needs a new pick after the date changes`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        assertTrue(
            ScheduleMath.needsNewRandomPick(
                task,
                at(day = 27, hour = 0, minute = 1),
                LocalDate.of(2026, 10, 26),
            )
        )
    }

    @Test
    fun `fixed tasks never need a random pick`() {
        val task = fixedTask(LocalTime.of(8, 30))
        assertFalse(ScheduleMath.needsNewRandomPick(task, at(day = 26, hour = 0), null))
    }

    // -----------------------------------------------------------------------
    // 随机挑时
    // -----------------------------------------------------------------------

    @Test
    fun `pick random time stays inside the window after the lead`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        val pick = ScheduleMath.pickRandomTime(task, Random(42), minLeadMinutes = 2)
        assertFalse("$pick 不应该早于窗口起点 + 2 分钟", pick.isBefore(LocalTime.of(19, 2)))
        assertTrue("$pick 应该早于窗口终点", pick.isBefore(LocalTime.of(22, 0)))
    }

    @Test
    fun `pick random time is deterministic with the same seed`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        val first = ScheduleMath.pickRandomTime(task, Random(7))
        val second = ScheduleMath.pickRandomTime(task, Random(7))
        assertEquals(first, second)
    }

    @Test
    fun `pick random time handles a window across midnight`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0))
        repeat(20) { seed ->
            val pick = ScheduleMath.pickRandomTime(task, Random(seed), minLeadMinutes = 2)
            val inWindow = !pick.isBefore(LocalTime.of(23, 2)) || pick.isBefore(LocalTime.of(1, 0))
            assertTrue("第 $seed 个种子挑到 $pick，应该落在 23:00–01:00 窗口里", inWindow)
            assertTrue("$pick 不应该落在窗口外的白天", pick.isAfter(LocalTime.of(22, 59)) || pick.isBefore(LocalTime.of(1, 0)))
        }
    }

    @Test
    fun `pick random time for a fixed task returns its time`() {
        val task = fixedTask(LocalTime.of(8, 30))
        assertEquals(LocalTime.of(8, 30), ScheduleMath.pickRandomTime(task, Random(1)))
    }

    @Test
    fun `pick from now stays after now plus lead and before the window end`() {
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0))
        val pick = requireNotNull(
            ScheduleMath.pickRandomTime(task, at(day = 26, hour = 20), Random(3), minLeadMinutes = 2)
        ) { "20:00 时 19:00–22:00 的窗口应该还能挑" }
        assertFalse("$pick 不应该早于 20:02", pick.isBefore(LocalTime.of(20, 2)))
        assertTrue("$pick 应该早于 22:00", pick.isBefore(LocalTime.of(22, 0)))
    }

    @Test
    fun `pick from now can still use the window that started yesterday`() {
        val task = randomTask(LocalTime.of(23, 0), LocalTime.of(1, 0))
        // 周二 00:10，周一开始的窗口还剩 00:12–00:59。
        val pick = requireNotNull(
            ScheduleMath.pickRandomTime(task, at(day = 27, hour = 0, minute = 10), Random(11), minLeadMinutes = 2)
        ) { "00:10 时昨天的 23:00–01:00 窗口应该还能挑" }
        assertFalse("$pick 不应该早于 00:12", pick.isBefore(LocalTime.of(0, 12)))
        assertTrue("$pick 应该早于 01:00", pick.isBefore(LocalTime.of(1, 0)))
    }

    @Test
    fun `pick from now rolls to the next matching window`() {
        // 工作日任务，周五 22:30 时今天的窗口已经结束 → 下一个窗口是下周一 19:00–22:00。
        val task = randomTask(LocalTime.of(19, 0), LocalTime.of(22, 0), repeat = ScheduleRepeat.Weekdays)
        val pick = requireNotNull(
            ScheduleMath.pickRandomTime(task, at(day = 30, hour = 22, minute = 30), Random(5), minLeadMinutes = 2)
        ) { "周五 22:30 应该能滚到下周一的工作日窗口" }
        assertFalse("$pick 不应该早于窗口起点", pick.isBefore(LocalTime.of(19, 0)))
        assertTrue("$pick 应该早于窗口终点", pick.isBefore(LocalTime.of(22, 0)))
    }

    @Test
    fun `pick from now returns null when no window ever matches`() {
        val task = randomTask(
            LocalTime.of(19, 0),
            LocalTime.of(22, 0),
            repeat = ScheduleRepeat.Custom(emptySet()),
        )
        assertNull(ScheduleMath.pickRandomTime(task, at(day = 26, hour = 18), Random(5)))
    }

    // -----------------------------------------------------------------------
    // 免打扰时段
    // -----------------------------------------------------------------------

    @Test
    fun `quiet hours across midnight includes both sides and the boundaries`() {
        val start = LocalTime.of(23, 30)
        val end = LocalTime.of(7, 30)
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(23, 30), start, end))
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(23, 59), start, end))
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(0, 0), start, end))
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(7, 29), start, end))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(7, 30), start, end))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(23, 29), start, end))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(12, 0), start, end))
    }

    @Test
    fun `quiet hours inside one day`() {
        val start = LocalTime.of(13, 0)
        val end = LocalTime.of(14, 0)
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(13, 0), start, end))
        assertTrue(ScheduleMath.isQuietHour(LocalTime.of(13, 59), start, end))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(14, 0), start, end))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(12, 59), start, end))
    }

    @Test
    fun `quiet start equal to quiet end means disabled`() {
        val same = LocalTime.of(3, 0)
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(3, 0), same, same))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(0, 0), same, same))
        assertFalse(ScheduleMath.isQuietHour(LocalTime.of(12, 0), same, same))
    }

    // -----------------------------------------------------------------------
    // 凌晨 1–8 点静默
    // -----------------------------------------------------------------------

    @Test
    fun `night silence is one am inclusive to eight am exclusive`() {
        assertFalse(ScheduleMath.isNightSilence(LocalTime.of(0, 59)))
        assertTrue(ScheduleMath.isNightSilence(LocalTime.of(1, 0)))
        assertTrue(ScheduleMath.isNightSilence(LocalTime.of(7, 59)))
        assertFalse(ScheduleMath.isNightSilence(LocalTime.of(8, 0)))
        assertFalse(ScheduleMath.isNightSilence(LocalTime.of(0, 0)))
        assertFalse(ScheduleMath.isNightSilence(LocalTime.of(12, 0)))
        assertFalse(ScheduleMath.isNightSilence(LocalTime.of(23, 59)))
    }

    // -----------------------------------------------------------------------
    // 该不该跳过
    // -----------------------------------------------------------------------

    @Test
    fun `global switch off wins over everything else`() {
        val settings = normalSettings.copy(proactiveEnabled = false)
        val disabledTask = fixedTask(LocalTime.of(8, 30)).copy(enabled = false)
        // 凌晨 2 点也在免打扰 / 凌晨静默里，但仍报总开关。
        assertEquals(SkipReason.GlobalDisabled, ScheduleMath.shouldSkip(disabledTask, at(day = 26, hour = 2), settings))
    }

    @Test
    fun `quiet hours are reported during the quiet window`() {
        assertEquals(SkipReason.QuietHours, ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 23, minute = 40), normalSettings))
        assertEquals(SkipReason.QuietHours, ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 7, minute = 0), normalSettings))
    }

    @Test
    fun `night silence is reported when quiet hours are disabled`() {
        val noQuiet = ScheduleSkipSettings(
            proactiveEnabled = true,
            quietStart = LocalTime.of(9, 0),
            quietEnd = LocalTime.of(9, 0),
        )
        assertEquals(SkipReason.NightSilence, ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 2), noQuiet))
    }

    @Test
    fun `task disabled is reported last`() {
        val disabledTask = fixedTask(LocalTime.of(8, 30)).copy(enabled = false)
        assertEquals(SkipReason.TaskDisabled, ScheduleMath.shouldSkip(disabledTask, at(day = 26, hour = 9), normalSettings))
    }

    @Test
    fun `quiet hours are checked before night silence`() {
        // 02:00 同时命中免打扰和凌晨静默，按任务书 6.6 的顺序报 QuietHours。
        assertEquals(SkipReason.QuietHours, ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 2), normalSettings))
    }

    @Test
    fun `nothing is skipped during a plain daytime moment`() {
        assertNull(ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 9), normalSettings))
        // 23:00 还没到 23:30 的免打扰起点。
        assertNull(ScheduleMath.shouldSkip(enabledTask(), at(day = 26, hour = 23, minute = 0), normalSettings))
    }

    // -----------------------------------------------------------------------
    // 开机补跑的迟到判定
    // -----------------------------------------------------------------------

    private val scheduledAt: ZonedDateTime = at(day = 26, hour = 8, minute = 30)

    @Test
    fun `fourteen minutes late is within grace`() {
        assertFalse(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.plusMinutes(14)))
    }

    @Test
    fun `fifteen minutes late is still within grace`() {
        assertFalse(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.plusMinutes(15)))
    }

    @Test
    fun `sixteen minutes late is beyond grace`() {
        assertTrue(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.plusMinutes(16)))
    }

    @Test
    fun `on time or early is not late`() {
        assertFalse(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt))
        assertFalse(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.minusMinutes(1)))
    }

    @Test
    fun `grace minutes can be customized`() {
        assertFalse(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.plusMinutes(10), graceMinutes = 10))
        assertTrue(ScheduleMath.isLateBeyondGrace(scheduledAt, scheduledAt.plusMinutes(11), graceMinutes = 10))
    }

    // -----------------------------------------------------------------------
    // 两个种子任务
    // -----------------------------------------------------------------------

    @Test
    fun `seed tasks are the morning and night dailies`() {
        val seeds = ScheduleMath.seedTasks(at(day = 26, hour = 6), ::readAsset)

        assertEquals(2, seeds.size)
        val (morning, night) = seeds

        assertEquals("早上醒来", morning.title)
        assertEquals(ScheduleTiming.Fixed(LocalTime.of(8, 30)), morning.timing)
        assertEquals(ScheduleRepeat.Daily, morning.repeat)
        assertTrue(morning.enabled)
        assertNull(morning.lastSentAt)
        assertNull(morning.todayPickedTime)

        assertEquals("睡前", night.title)
        assertEquals(ScheduleTiming.Fixed(LocalTime.of(23, 30)), night.timing)
        assertEquals(ScheduleRepeat.Daily, night.repeat)
        assertTrue(night.enabled)
        assertNull(night.lastSentAt)
        assertNull(night.todayPickedTime)

        // id 稳定且互不相同（首次启动创建、以后不能重复建）。
        assertTrue(morning.id != night.id)
        assertEquals("schedule-seed-morning", morning.id)
        assertEquals("schedule-seed-night", night.id)
    }

    @Test
    fun `seed task hints are copied verbatim from the alarm prompts`() {
        val seeds = ScheduleMath.seedTasks(at(day = 26, hour = 6), ::readAsset)

        assertEquals(readAsset("whale/prompts/alarm_morning.txt").trim(), seeds[0].hint)
        assertEquals(readAsset("whale/prompts/alarm_night.txt").trim(), seeds[1].hint)
        // 和 Hermes 的提示词同文，不是随手写的占位文案。
        assertTrue("早上种子 hint 应该来自 alarm_morning 提示词", seeds[0].hint!!.contains("闹钟：早上了"))
        assertTrue("睡前种子 hint 应该来自 alarm_night 提示词", seeds[1].hint!!.contains("闹钟：快睡觉了"))
    }

    @Test
    fun `seed tasks load prompts relative to the assets root`() {
        val requested = mutableListOf<String>()
        ScheduleMath.seedTasks(at(day = 26, hour = 6)) { path ->
            requested += path
            readAsset(path)
        }
        assertEquals(
            listOf("whale/prompts/alarm_morning.txt", "whale/prompts/alarm_night.txt"),
            requested,
        )
    }

    // -----------------------------------------------------------------------
    // 测试辅助
    // -----------------------------------------------------------------------

    private val normalSettings = ScheduleSkipSettings(
        proactiveEnabled = true,
        quietStart = LocalTime.of(23, 30),
        quietEnd = LocalTime.of(7, 30),
    )

    private fun enabledTask(): ScheduleTask = fixedTask(LocalTime.of(8, 30))

    private fun fixedTask(
        time: LocalTime,
        repeat: ScheduleRepeat = ScheduleRepeat.Daily,
    ): ScheduleTask = ScheduleTask(
        id = "task-fixed",
        title = "测试任务",
        hint = "测试提示",
        timing = ScheduleTiming.Fixed(time),
        repeat = repeat,
        enabled = true,
    )

    private fun randomTask(
        windowStart: LocalTime,
        windowEnd: LocalTime,
        repeat: ScheduleRepeat = ScheduleRepeat.Daily,
    ): ScheduleTask = ScheduleTask(
        id = "task-random",
        title = "测试任务",
        hint = "测试提示",
        timing = ScheduleTiming.Random(windowStart, windowEnd),
        repeat = repeat,
        enabled = true,
    )

    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
        month: Int = 10,
    ): ZonedDateTime = ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE)

    /** 单元测试直接读 `src/main/assets`，保证种子 hint 和真正打包进 APK 的文件一致。 */
    private fun readAsset(path: String): String {
        val candidates = listOf(
            File("src/main/assets", path),
            File("app/src/main/assets", path),
            File(System.getProperty("user.dir"), "src/main/assets/$path"),
            File(System.getProperty("user.dir"), "app/src/main/assets/$path"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 assets 文件 $path，试过：$candidates")
        return file.readText()
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
