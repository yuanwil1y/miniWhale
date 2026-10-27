package whaleui.nightsky.ltd.alarm

import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.ai.fakeContext
import whaleui.nightsky.ltd.domain.settings.AppSettings
import whaleui.nightsky.ltd.data.HerAlarmRepository
import whaleui.nightsky.ltd.domain.settings.SettingsRepository
import whaleui.nightsky.ltd.data.db.HerAlarmEntity
import whaleui.nightsky.ltd.data.db.WhaleHerAlarmDao

/** 她自己定的一次性闹钟（her_alarm）到点后的处理。 */
class WhaleHerAlarmSchedulerTest {

    // ---------------------------------------------------------------- W-023

    @Test
    fun `a failed turn keeps her alarm pending`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        harness.runner.failure = IllegalStateException("示例失败")

        harness.scheduler.handleFire(alarm.id, alarm.note)

        assertFalse("没真正开一轮之前不能标记已触发", harness.fired(alarm.id))
    }

    @Test
    fun `a turn that ran marks her alarm fired`() = runTest {
        val harness = Harness()
        val alarm = harness.add()

        harness.scheduler.handleFire(alarm.id, alarm.note)

        assertEquals(listOf(alarm.note), harness.runner.notes)
        assertTrue(harness.fired(alarm.id))
    }

    @Test
    fun `a silent turn also marks her alarm fired`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        harness.runner.result = AlarmTurnOutcome.Silent

        assertEquals(FireOutcome.Silent, harness.scheduler.handleFire(alarm.id, alarm.note))
        assertTrue(harness.fired(alarm.id))
        assertEquals(listOf(alarm.id), harness.retryCancels)
    }

    @Test
    fun `busy postpones her alarm with an alarm and keeps it pending`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        harness.runner.result = AlarmTurnOutcome.Busy

        val outcome = harness.scheduler.handleFire(alarm.id, alarm.note)

        assertEquals(FireOutcome.Postponed, outcome)
        assertFalse(harness.fired(alarm.id))
        val retry = harness.retries.last()
        assertEquals(1, retry.attempt)
        assertEquals(harness.now.plusMinutes(2).toInstant().toEpochMilli(), retry.triggerAtMillis)
        // 双保险里的另一个闹钟取消掉
        assertEquals(listOf(alarm.id), harness.alarmCancels)
    }

    @Test
    fun `postponing has a limit and giving up does not pretend it was sent`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        harness.runner.result = AlarmTurnOutcome.Busy
        var outcome = harness.scheduler.handleFire(alarm.id, alarm.note)
        while (outcome == FireOutcome.Postponed) {
            harness.clockNow = harness.clockNow.plusMinutes(2)
            outcome = harness.scheduler.handleFire(alarm.id, alarm.note, attempt = harness.retries.last().attempt)
        }
        assertEquals(FireOutcome.GaveUp, outcome)
        assertEquals(AlarmRetryPolicy.MAX_ATTEMPTS, harness.runner.notes.size)
        assertFalse(harness.fired(alarm.id))
    }

    @Test
    fun `watchdog after process death marks fired when she already replied`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        val startedAt = harness.now.toInstant().toEpochMilli()
        harness.clockNow = harness.now.plusMinutes(10)
        harness.runner.repliedSince = { it == startedAt }

        val outcome = harness.scheduler.handleFire(alarm.id, alarm.note, attempt = 1, startedAtMillis = startedAt)

        assertEquals(FireOutcome.Sent, outcome)
        assertTrue(harness.runner.notes.isEmpty())
        assertTrue(harness.fired(alarm.id))
    }

    @Test
    fun `a second delivery after it fired is ignored`() = runTest {
        val harness = Harness()
        val alarm = harness.add()
        harness.scheduler.handleFire(alarm.id, alarm.note)
        assertEquals(FireOutcome.Duplicate, harness.scheduler.handleFire(alarm.id, alarm.note))
        assertEquals(1, harness.runner.notes.size)
    }

    // ---------------------------------------------------------------- 夹具

    private class Harness {
        val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 26, 15, 0, 0, 0, ZoneId.of("Asia/Shanghai"))
        val dao = FakeHerAlarmDao()
        val repository = HerAlarmRepository(dao, ZoneId.of("Asia/Shanghai"))
        val runner = Runner()
        val retries = mutableListOf<HerAlarmFire>()
        val retryCancels = mutableListOf<String>()
        val alarmCancels = mutableListOf<String>()
        var clockNow: ZonedDateTime = now
        val scheduler = WhaleHerAlarmScheduler(
            context = fakeContext(),
            herAlarms = repository,
            settings = FakeSettings(AppSettings()),
            runner = runner,
            clock = { clockNow },
            alarmSetter = {},
            alarmCanceller = { alarmCancels += it },
            retrySetter = { retries += it },
            retryCanceller = { retryCancels += it },
        )

        suspend fun add() = repository.add(now.toInstant().toEpochMilli(), "（闹钟：提醒他喝水）")

        suspend fun fired(id: String): Boolean = repository.observeAll().first().single { it.id == id }.fired
    }

    private class Runner : AlarmTurnRunner {
        val notes = mutableListOf<String>()
        var failure: Throwable? = null
        var result = AlarmTurnOutcome.Sent
        var repliedSince: (Long) -> Boolean = { false }

        override suspend fun runAlarmTurn(alarmNote: String, taskId: String?): AlarmTurnOutcome {
            failure?.let { throw it }
            notes += alarmNote
            return result
        }

        override suspend fun hasRepliedSince(sinceMillis: Long): Boolean = repliedSince(sinceMillis)
    }

    private class FakeHerAlarmDao : WhaleHerAlarmDao {
        private val rows = MutableStateFlow<List<HerAlarmEntity>>(emptyList())

        override fun observeAll(): Flow<List<HerAlarmEntity>> = rows.map { list -> list.sortedBy { it.fireAt } }

        override suspend fun nextPending(now: Long): HerAlarmEntity? =
            rows.value.filter { !it.fired && it.fireAt >= now }.minByOrNull { it.fireAt }

        override suspend fun pendingBetween(start: Long, end: Long): List<HerAlarmEntity> =
            rows.value.filter { !it.fired && it.fireAt >= start && it.fireAt < end }.sortedBy { it.fireAt }

        override suspend fun upsert(alarm: HerAlarmEntity) {
            rows.update { list -> list.filterNot { it.id == alarm.id } + alarm }
        }

        override suspend fun markFired(id: String): Int {
            var count = 0
            rows.update { list -> list.map { if (it.id == id) it.copy(fired = true).also { count++ } else it } }
            return count
        }

        override suspend fun deleteById(id: String): Int {
            val before = rows.value.size
            rows.update { list -> list.filterNot { it.id == id } }
            return before - rows.value.size
        }
    }

    private class FakeSettings(initial: AppSettings) : SettingsRepository {
        private val state = MutableStateFlow(initial)
        override val settings: Flow<AppSettings> = state
        override suspend fun setVoiceId(voiceId: String) = state.update { it.copy(voiceId = voiceId) }
        override suspend fun setSpeechRate(rate: Float) = state.update { it.copy(speechRate = rate) }
        override suspend fun setHomeCity(city: String) = state.update { it.copy(homeCity = city) }
        override suspend fun setProactiveEnabled(enabled: Boolean) = state.update { it.copy(proactiveEnabled = enabled) }
        override suspend fun setQuietHours(start: LocalTime, end: LocalTime) =
            state.update { it.copy(quietStart = start, quietEnd = end) }
        override suspend fun setKeepAliveEnabled(enabled: Boolean) = state.update { it.copy(keepAliveEnabled = enabled) }
    }
}
