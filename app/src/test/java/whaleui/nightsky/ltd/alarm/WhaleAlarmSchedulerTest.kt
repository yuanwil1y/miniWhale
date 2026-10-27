package whaleui.nightsky.ltd.alarm

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.domain.settings.AppSettings
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.schedule.ScheduleMath
import whaleui.nightsky.ltd.schedule.SkipReason
import whaleui.nightsky.ltd.ui.schedule.FakeScheduleRepository
import whaleui.nightsky.ltd.domain.schedule.ScheduleRepeat
import whaleui.nightsky.ltd.domain.schedule.ScheduleTask
import whaleui.nightsky.ltd.domain.schedule.ScheduleTiming

/**
 * [WhaleAlarmScheduler] 的纯 JVM 单测：不碰真实 AlarmManager / Context / WorkManager。
 *
 * - 时间全部用固定 [ZonedDateTime]（[zone] 固定为 Asia/Shanghai），不读系统时钟；
 * - 随机用固定种子；
 * - 真正调系统闹钟的那一行用注入的 `alarmSetter` 记录器代替；离线分支用注入的
 *   `isNetworkAvailable` / `enqueueNetworkWait` 记录器代替 WorkManager。
 */
class WhaleAlarmSchedulerTest {

    // ------------------------------------------------------------ 注册 / 取消

    @Test
    fun scheduleAllRegistersNextFixedFire() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()

        assertEquals(1, harness.alarms.size)
        assertEquals(task.id, harness.alarms[0].taskId)
        assertEquals(zonedAt(2026, 10, 26, 10, 0), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun scheduleAllDoesNotArmDisabledTask() = runTest {
        val task = fixedTask(enabled = false)
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()

        assertTrue(harness.alarms.isEmpty())
        assertEquals(listOf(task.id), harness.cancelled)
    }

    @Test
    fun scheduleAllCancelsPreviouslyScheduledTaskThatGotDisabled() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()
        assertEquals(1, harness.alarms.size)

        harness.repository.setEnabled(task.id, false)
        harness.scheduler.scheduleAll()

        assertEquals(1, harness.alarms.size) // 没有新注册
        assertEquals(1, harness.cancelled.size)
        assertEquals(task.id, harness.cancelled[0])
    }

    @Test
    fun scheduleAllSkipsTaskWithoutFutureOccurrence() = runTest {
        val task = fixedTask(repeat = ScheduleRepeat.Custom(emptySet()))
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()

        assertTrue(harness.alarms.isEmpty())
    }

    @Test
    fun scheduleAllCancelsVanishedTask() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()
        harness.repository.delete(task.id)
        harness.scheduler.scheduleAll()

        assertEquals(1, harness.cancelled.size)
        assertEquals(task.id, harness.cancelled[0])
    }

    // ------------------------------------------------------------ 随机任务

    @Test
    fun scheduleAllPicksRandomTimeAndWritesItBack() = runTest {
        val task = randomTask()
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()

        val expectedPick = ScheduleMath.pickRandomTime(task, harness.now, Random(RANDOM_SEED))!!
        val stored = harness.repository.observeAll().first().single()
        assertEquals(expectedPick, stored.todayPickedTime)

        val expectedFireAt = ScheduleMath.nextFireAt(
            task.copy(todayPickedTime = expectedPick),
            harness.now,
            expectedPick,
        )!!.toInstant().toEpochMilli()
        assertEquals(1, harness.alarms.size)
        assertEquals(expectedFireAt, harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun secondScheduleAllDoesNotRepickTheRandomTime() = runTest {
        val task = randomTask()
        val harness = harness(listOf(task))
        harness.scheduler.scheduleAll()
        val firstPick = harness.repository.observeAll().first().single().todayPickedTime

        harness.scheduler.scheduleAll()
        val secondPick = harness.repository.observeAll().first().single().todayPickedTime

        assertEquals(firstPick, secondPick)
        assertEquals(ScheduleMath.pickRandomTime(task, harness.now, Random(RANDOM_SEED)), firstPick)
        assertEquals(2, harness.alarms.size)
        // 两次注册的是同一个触发时刻（幂等）。
        assertEquals(harness.alarms[0].triggerAtMillis, harness.alarms[1].triggerAtMillis)
    }

    @Test
    fun randomTaskAlreadySentTodayPicksFreshTimeForTomorrow() = runTest {
        val task = randomTask(
            todayPickedTime = LocalTime.of(9, 20),
            lastSentAt = zonedAt(2026, 10, 26, 9, 30),
        )
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.scheduler.scheduleAll()

        val expectedPick = ScheduleMath.pickRandomTime(
            task,
            harness.now.plusDays(1),
            Random(RANDOM_SEED),
        )!!
        val stored = harness.repository.observeAll().first().single()
        assertEquals(expectedPick, stored.todayPickedTime)
        assertEquals(1, harness.alarms.size)
        assertEquals(
            zonedAt(2026, 10, 27, expectedPick.hour, expectedPick.minute),
            harness.alarms[0].triggerAtMillis,
        )
    }

    @Test
    fun randomTaskFiredTodaySchedulesNextForTomorrowOnly() = runTest {
        val pick = LocalTime.of(10, 30)
        val task = randomTask(todayPickedTime = pick)
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 30))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 30))

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(1, harness.alarms.size)
        val fireDate = Instant.ofEpochMilli(harness.alarms[0].triggerAtMillis).atZone(zone).toLocalDate()
        assertEquals(harness.now.toLocalDate().plusDays(1), fireDate)
    }

    // ------------------------------------------------------------ 触发

    @Test
    fun handleFireRunsRunnerWritesLastSentAtAndSchedulesNext() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(listOf("示例提示"), harness.runner.notes)
        assertEquals(zonedAt(2026, 10, 26, 10, 0), harness.repository.observeAll().first().single().lastSentAt)
        assertEquals(1, harness.alarms.size)
        assertEquals(zonedAt(2026, 10, 27, 10, 0), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun handleFireSilentStillSchedulesNextWithoutWritingLastSentAt() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0), runnerResult = false)
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Silent, outcome)
        assertEquals(listOf("示例提示"), harness.runner.notes)
        assertNull(harness.repository.observeAll().first().single().lastSentAt)
        assertEquals(zonedAt(2026, 10, 27, 10, 0), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun handleFireAfterGraceSkipsRunnerAndOnlySchedulesNext() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 16))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.LateSkipped, outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertNull(harness.repository.observeAll().first().single().lastSentAt)
        assertEquals(zonedAt(2026, 10, 27, 10, 0), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun handleFireWithinGraceStillRuns() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 15))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(1, harness.runner.notes.size)
    }

    @Test
    fun handleFireSkipsWhenProactiveDisabled() = runTest {
        val task = fixedTask()
        val harness = harness(
            listOf(task),
            now = zonedDateTimeAt(2026, 10, 26, 10, 0),
            settings = AppSettings(proactiveEnabled = false),
        )
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Skipped(SkipReason.GlobalDisabled), outcome)
        assertTrue(harness.runner.notes.isEmpty())
        // 设置可能改回来，所以仍然排下一次。
        assertEquals(1, harness.alarms.size)
    }

    @Test
    fun handleFireSkipsInsideQuietHours() = runTest {
        val task = fixedTask(time = LocalTime.of(23, 30))
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 23, 40))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 23, 30))

        assertEquals(FireOutcome.Skipped(SkipReason.QuietHours), outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertEquals(zonedAt(2026, 10, 27, 23, 30), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun handleFireSkipsDuringNightSilence() = runTest {
        val task = fixedTask(time = LocalTime.of(2, 0))
        val harness = harness(
            listOf(task),
            now = zonedDateTimeAt(2026, 10, 26, 2, 0),
            // 不设免打扰，单独验证 1–8 点静默。
            settings = AppSettings(quietStart = LocalTime.of(0, 0), quietEnd = LocalTime.of(0, 0)),
        )
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 2, 0))

        assertEquals(FireOutcome.Skipped(SkipReason.NightSilence), outcome)
        assertTrue(harness.runner.notes.isEmpty())
    }

    @Test
    fun handleFireSkipsAndCancelsWhenTaskDisabled() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.repository.setEnabled(task.id, false)
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Skipped(SkipReason.TaskDisabled), outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertEquals(listOf(task.id), harness.cancelled)
        assertTrue(harness.alarms.isEmpty())
    }

    @Test
    fun handleFireForUnknownTaskDoesNothing() = runTest {
        val harness = harness(emptyList())
        val outcome = harness.scheduler.handleFire("missing-task", zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.UnknownTask, outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertTrue(harness.alarms.isEmpty())
    }

    @Test
    fun handleFireOfflineEnqueuesNetworkWaitInsteadOfRunning() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.networkUp = false
        val scheduledAt = zonedAt(2026, 10, 26, 10, 0)
        val outcome = harness.scheduler.handleFire(task.id, scheduledAt)

        assertEquals(FireOutcome.WaitingForNetwork, outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertEquals(listOf(task.id to scheduledAt), harness.networkWaits)
        assertTrue(harness.alarms.isEmpty())
    }

    @Test
    fun workerPathRunsEvenWithoutNetworkCheck() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.networkUp = false
        val outcome = harness.scheduler.runAlarmTurnWhenOnline(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(1, harness.runner.notes.size)
        assertTrue(harness.networkWaits.isEmpty())
        assertEquals(zonedAt(2026, 10, 27, 10, 0), harness.alarms[0].triggerAtMillis)
    }

    @Test
    fun workerPathIgnoresGraceWindowBecauseItWasWaitingForNetwork() = runTest {
        val task = fixedTask()
        // 闹钟 10:00 响的时候没网，等到 10:20 网络恢复：仍然补这一轮，不算迟到。
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 20))
        val outcome = harness.scheduler.runAlarmTurnWhenOnline(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(1, harness.runner.notes.size)
    }

    @Test
    fun runnerFailureIsSwallowedAndNextIsStillScheduled() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.runner.failure = IllegalStateException("示例失败")
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Failed, outcome)
        assertNull(harness.repository.observeAll().first().single().lastSentAt)
        assertEquals(1, harness.alarms.size)
    }

    @Test
    fun registerBootRescheduleRearmsAllTasks() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task))
        harness.scheduler.registerBootReschedule()

        assertEquals(1, harness.alarms.size)
        assertEquals(task.id, harness.alarms[0].taskId)
    }

    // ------------------------------------------------------------ W-007

    @Test
    fun nextFireIsArmedBeforeTheTurnRuns() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        var armedWhenTurnStarted: List<Long> = emptyList()
        harness.runner.onRun = { armedWhenTurnStarted = harness.alarms.map { it.triggerAtMillis } }

        harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(
            "进程在生成中途被杀也不能丢下一次：开这一轮之前就要排好",
            listOf(zonedAt(2026, 10, 27, 10, 0)),
            armedWhenTurnStarted,
        )
    }

    @Test
    fun watchdogIsRegisteredBeforeTheTurnAndCancelledWhenItEnds() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        var retriesWhenTurnStarted: List<AlarmRetry> = emptyList()
        harness.runner.onRun = { retriesWhenTurnStarted = harness.retries.toList() }

        harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        val watchdog = retriesWhenTurnStarted.single()
        assertEquals(1, watchdog.attempt)
        assertEquals(zonedAt(2026, 10, 26, 10, 0), watchdog.startedAtMillis)
        assertEquals(zonedAt(2026, 10, 26, 10, 10), watchdog.triggerAtMillis)
        assertEquals(listOf(task.id), harness.retryCancels)
    }

    @Test
    fun watchdogAfterProcessDeathOnlyRecordsWhatWasAlreadySent() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 10))
        val startedAt = zonedAt(2026, 10, 26, 10, 0)
        harness.runner.repliedSince = { it == startedAt }

        val outcome = harness.scheduler.handleFire(task.id, startedAt, attempt = 1, startedAtMillis = startedAt)

        assertEquals(FireOutcome.Sent, outcome)
        assertTrue("已经发出去了，不能再发一遍", harness.runner.notes.isEmpty())
        assertEquals(startedAt, harness.repository.observeAll().first().single().lastSentAt)
    }

    @Test
    fun watchdogAfterProcessDeathWithoutReplyRunsTheTurnAgain() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 10))
        val startedAt = zonedAt(2026, 10, 26, 10, 0)

        val outcome = harness.scheduler.handleFire(task.id, startedAt, attempt = 1, startedAtMillis = startedAt)

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(1, harness.runner.notes.size)
    }

    @Test
    fun duplicateDeliveryOfTheSameFireIsIgnored() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Duplicate, outcome)
        assertEquals(1, harness.runner.notes.size)
    }

    // ------------------------------------------------------------ W-023

    @Test
    fun busyTurnIsPostponedWithAnAlarmNotDropped() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.runner.result = AlarmTurnOutcome.Busy

        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0))

        assertEquals(FireOutcome.Postponed, outcome)
        val retry = harness.retries.last()
        assertEquals(1, retry.attempt)
        assertEquals(0L, retry.startedAtMillis)
        assertEquals(zonedAt(2026, 10, 26, 10, 2), retry.triggerAtMillis)
        assertEquals(zonedAt(2026, 10, 26, 10, 0), retry.scheduledAtMillis)
        assertNull(harness.repository.observeAll().first().single().lastSentAt)
        // 下一次照常已经排好
        assertEquals(zonedAt(2026, 10, 27, 10, 0), harness.alarms.single().triggerAtMillis)
    }

    @Test
    fun busyCanBePostponedSeveralTimesThenGivesUp() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 0))
        harness.runner.result = AlarmTurnOutcome.Busy
        val scheduledAt = zonedAt(2026, 10, 26, 10, 0)

        var attempt = 0
        var outcome = harness.scheduler.handleFire(task.id, scheduledAt)
        while (outcome == FireOutcome.Postponed) {
            attempt = harness.retries.last().attempt
            harness.now = harness.now.plusMinutes(2)
            outcome = harness.scheduler.handleFire(task.id, scheduledAt, attempt = attempt)
        }

        assertEquals(FireOutcome.GaveUp, outcome)
        assertEquals(AlarmRetryPolicy.MAX_ATTEMPTS, harness.runner.notes.size)
        assertEquals(AlarmRetryPolicy.MAX_ATTEMPTS - 1, attempt)
        // 顺延期间不重复排「下一次」，也不会在当天再响
        assertTrue(harness.alarms.all { it.triggerAtMillis == zonedAt(2026, 10, 27, 10, 0) })
        assertEquals(task.id, harness.retryCancels.last())
    }

    @Test
    fun postponedTurnThatSucceedsWritesLastSentAt() = runTest {
        val task = fixedTask()
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 10, 2))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 10, 0), attempt = 1)

        assertEquals(FireOutcome.Sent, outcome)
        assertEquals(zonedAt(2026, 10, 26, 10, 2), harness.repository.observeAll().first().single().lastSentAt)
    }

    @Test
    fun retryThatLandsInQuietHoursIsSkippedWithoutRearming() = runTest {
        val task = fixedTask(time = LocalTime.of(23, 28))
        val harness = harness(listOf(task), now = zonedDateTimeAt(2026, 10, 26, 23, 30))
        val outcome = harness.scheduler.handleFire(task.id, zonedAt(2026, 10, 26, 23, 28), attempt = 1)

        assertEquals(FireOutcome.Skipped(SkipReason.QuietHours), outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertTrue(harness.alarms.isEmpty())
        assertEquals(listOf(task.id), harness.retryCancels)
    }

    // ------------------------------------------------------------ 测试夹具

    private fun harness(
        seed: List<ScheduleTask>,
        now: ZonedDateTime = zonedDateTimeAt(2026, 10, 26, 9, 0),
        settings: AppSettings = AppSettings(),
        runnerResult: Boolean = true,
        randomSeed: Int = RANDOM_SEED,
    ) = Harness(seed, now, settings, runnerResult, randomSeed)

    private class Harness(
        seed: List<ScheduleTask>,
        initialNow: ZonedDateTime,
        initialSettings: AppSettings,
        runnerResult: Boolean,
        randomSeed: Int,
    ) {
        val retries = mutableListOf<AlarmRetry>()
        val retryCancels = mutableListOf<String>()
        var now: ZonedDateTime = initialNow
        var networkUp: Boolean = true
        val repository = FakeScheduleRepository(seed)
        val settings = FakeSettingsRepository(initialSettings)
        val runner = RecordingRunner(if (runnerResult) AlarmTurnOutcome.Sent else AlarmTurnOutcome.Silent)
        val alarms = mutableListOf<AlarmEvent>()
        val cancelled = mutableListOf<String>()
        val networkWaits = mutableListOf<Pair<String, Long>>()

        val scheduler = WhaleAlarmScheduler(
            context = null,
            repository = repository,
            settings = settings,
            runner = runner,
            clock = { now },
            random = Random(randomSeed),
            alarmSetter = { triggerAtMillis, taskId ->
                alarms += AlarmEvent(triggerAtMillis, taskId)
            },
            alarmCanceller = { taskId ->
                cancelled += taskId
            },
            isNetworkAvailable = { networkUp },
            enqueueNetworkWait = { taskId, scheduledAtMillis ->
                networkWaits += taskId to scheduledAtMillis
            },
            retrySetter = { retries += it },
            retryCanceller = { retryCancels += it },
        )
    }

    private data class AlarmEvent(
        val triggerAtMillis: Long,
        val taskId: String,
    )

    private class RecordingRunner(var result: AlarmTurnOutcome) : AlarmTurnRunner {
        val notes = mutableListOf<String>()
        var failure: Throwable? = null
        var onRun: () -> Unit = {}
        var repliedSince: (Long) -> Boolean = { false }

        override suspend fun runAlarmTurn(alarmNote: String, taskId: String?): AlarmTurnOutcome {
            onRun()
            failure?.let { throw it }
            notes += alarmNote
            return result
        }

        override suspend fun hasRepliedSince(sinceMillis: Long): Boolean = repliedSince(sinceMillis)
    }

    private class FakeSettingsRepository(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state

        override suspend fun setVoiceId(voiceId: String) {
            state.update { it.copy(voiceId = voiceId) }
        }

        override suspend fun setSpeechRate(rate: Float) {
            state.update { it.copy(speechRate = rate) }
        }

        override suspend fun setHomeCity(city: String) {
            state.update { it.copy(homeCity = city) }
        }

        override suspend fun setProactiveEnabled(enabled: Boolean) {
            state.update { it.copy(proactiveEnabled = enabled) }
        }

        override suspend fun setQuietHours(start: LocalTime, end: LocalTime) {
            state.update { it.copy(quietStart = start, quietEnd = end) }
        }

        override suspend fun setKeepAliveEnabled(enabled: Boolean) {
            state.update { it.copy(keepAliveEnabled = enabled) }
        }
    }

    private fun fixedTask(
        id: String = "task-fixed",
        time: LocalTime = LocalTime.of(10, 0),
        enabled: Boolean = true,
        repeat: ScheduleRepeat = ScheduleRepeat.Daily,
        lastSentAt: Long? = null,
    ) = ScheduleTask(
        id = id,
        title = "示例任务",
        hint = "示例提示",
        timing = ScheduleTiming.Fixed(time),
        repeat = repeat,
        enabled = enabled,
        lastSentAt = lastSentAt,
    )

    private fun randomTask(
        id: String = "task-random",
        windowStart: LocalTime = LocalTime.of(10, 0),
        windowEnd: LocalTime = LocalTime.of(11, 0),
        enabled: Boolean = true,
        repeat: ScheduleRepeat = ScheduleRepeat.Daily,
        lastSentAt: Long? = null,
        todayPickedTime: LocalTime? = null,
    ) = ScheduleTask(
        id = id,
        title = "示例随机任务",
        hint = "示例随机提示",
        timing = ScheduleTiming.Random(windowStart, windowEnd),
        repeat = repeat,
        enabled = enabled,
        lastSentAt = lastSentAt,
        todayPickedTime = todayPickedTime,
    )

    private companion object {
        const val RANDOM_SEED = 20261026

        val zone: ZoneId = ZoneId.of("Asia/Shanghai")

        fun zonedDateTimeAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
            ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)

        fun zonedAt(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            zonedDateTimeAt(year, month, day, hour, minute).toInstant().toEpochMilli()
    }
}
