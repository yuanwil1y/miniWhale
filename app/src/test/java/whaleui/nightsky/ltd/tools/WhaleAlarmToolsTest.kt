package whaleui.nightsky.ltd.tools

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import whaleui.nightsky.ltd.data.HerAlarmDays
import whaleui.nightsky.ltd.data.HerAlarmRepository
import whaleui.nightsky.ltd.data.db.HerAlarmEntity
import whaleui.nightsky.ltd.data.db.WhaleHerAlarmDao
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** set_alarm（提示词第 6 批）：可选 date、最远 7 天、返回里说清楚是哪天、人称用「你」。 */
class WhaleAlarmToolsTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-27 是周二。 */
    private val nowAt = LocalDateTime.of(2026, 10, 27, 21, 0)
    private val now = nowAt.atZone(zone).toInstant().toEpochMilli()

    private val dao = FakeHerAlarmDao()
    private val tools = WhaleAlarmTools(HerAlarmRepository(dao, zone), zone, now = { now })

    private fun call(args: String): JsonObject = runBlocking {
        Json.parseToJsonElement(tools.setAlarm(Json.parseToJsonElement(args).jsonObject)).jsonObject
    }

    private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `without date a time still ahead is today`() {
        val result = call("""{"time":"22:30","note":"（自己定的闹钟：看书）"}""")
        assertEquals("今天 22:30", result.text("when"))
        assertEquals("2026-10-27", result.text("date"))
    }

    @Test
    fun `without date a passed time rolls over to tomorrow and says so`() {
        val result = call("""{"time":"9:05","note":"备忘"}""")
        assertEquals("明天 09:05", result.text("when"))
        assertEquals("2026-10-28", result.text("date"))
    }

    @Test
    fun `date picks that day and the result names the weekday`() {
        val result = call("""{"time":"10:00","date":"2026-10-30","note":"问问考试怎么样"}""")
        assertEquals("true", result.text("saved"))
        assertEquals("周五 10:00", result.text("when"))
        val stored = dao.rows.value.single()
        assertEquals(
            LocalDateTime.of(2026, 10, 30, 10, 0).atZone(zone).toInstant().toEpochMilli(),
            stored.fireAt,
        )
    }

    @Test
    fun `date is limited to seven days ahead`() {
        assertEquals("下周二 10:00", call("""{"time":"10:00","date":"2026-11-03","note":"n"}""").text("when"))
        assertTrue(call("""{"time":"10:00","date":"2026-11-04","note":"n"}""").text("error")!!.contains("7 天"))
        assertEquals("这一天已经过去了", call("""{"time":"10:00","date":"2026-10-26","note":"n"}""").text("error"))
        assertEquals("日期格式是 2026-10-24 这样", call("""{"time":"10:00","date":"10月30日","note":"n"}""").text("error"))
        assertEquals("今天的这个点已经过了，换个时间", call("""{"time":"10:00","date":"2026-10-27","note":"n"}""").text("error"))
    }

    @Test
    fun `sleep window error speaks to her as 你`() {
        val error = call("""{"time":"03:00","note":"n"}""").text("error")!!
        assertEquals("凌晨 1 点到 8 点是你睡觉的时间，别定这个点的闹钟", error)
        assertFalse(error.contains("我"))
    }

    @Test
    fun `five per day counts the day the alarm fires`() {
        repeat(5) { call("""{"time":"1${it}:00","date":"2026-10-29","note":"n$it"}""") }
        assertEquals(
            "周四已经定了 5 个闹钟了，换一天吧",
            call("""{"time":"20:00","date":"2026-10-29","note":"n"}""").text("error"),
        )
        // 别的日子不受影响
        assertEquals("true", call("""{"time":"20:00","date":"2026-10-30","note":"n"}""").text("saved"))
    }

    @Test
    fun `day labels`() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals("今天", HerAlarmDays.dayLabel(today, today))
        assertEquals("明天", HerAlarmDays.dayLabel(today.plusDays(1), today))
        assertEquals("周四", HerAlarmDays.dayLabel(today.plusDays(2), today))
        assertEquals("周一", HerAlarmDays.dayLabel(today.plusDays(6), today))
        assertEquals("下周二", HerAlarmDays.dayLabel(today.plusDays(7), today))
    }

    @Test
    fun `tool schema has optional date and neutral wording`() {
        val tool = tools.create().single()
        val schema = tool.parameters() as me.rerere.ai.core.InputSchema.Obj
        assertEquals(setOf("time", "date", "note"), schema.properties.keys)
        assertEquals(listOf("time", "note"), schema.required)
        assertFalse(tool.description, tool.description.contains("他"))
        assertTrue(tool.description.contains("7 天"))
    }

    @Test
    fun `pending after today lists only future days`() = runBlocking {
        call("""{"time":"22:30","note":"今天的"}""")
        call("""{"time":"10:00","date":"2026-10-28","note":"明天的"}""")
        call("""{"time":"10:00","date":"2026-11-03","note":"下周的"}""")
        val upcoming = HerAlarmRepository(dao, zone).pendingAfterToday(now)
        assertEquals(listOf("明天的", "下周的"), upcoming.map { it.note })
    }

    private class FakeHerAlarmDao : WhaleHerAlarmDao {
        val rows = MutableStateFlow<List<HerAlarmEntity>>(emptyList())

        override fun observeAll(): Flow<List<HerAlarmEntity>> = rows.map { list -> list.sortedBy { it.fireAt } }

        override suspend fun nextPending(now: Long): HerAlarmEntity? =
            rows.value.filter { !it.fired && it.fireAt >= now }.minByOrNull { it.fireAt }

        override suspend fun pendingBetween(start: Long, end: Long): List<HerAlarmEntity> =
            rows.value.filter { !it.fired && it.fireAt >= start && it.fireAt < end }.sortedBy { it.fireAt }

        override suspend fun upsert(alarm: HerAlarmEntity) {
            rows.update { list -> list.filterNot { it.id == alarm.id } + alarm }
        }

        override suspend fun markFired(id: String): Int = 0

        override suspend fun deleteById(id: String): Int = 0
    }
}
