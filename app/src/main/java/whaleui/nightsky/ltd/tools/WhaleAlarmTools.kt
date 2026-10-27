package whaleui.nightsky.ltd.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.data.HerAlarmDays
import whaleui.nightsky.ltd.data.HerAlarmRepository
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * `set_alarm(time, note, date?)`（任务书 6.5；提示词第 6 批加 `date`）：她给自己定一个一次性闹钟。
 *
 * 规则：
 * - `date` 可不填：不填就是今天的这个点，已经过了就顺延到明天；填了就是那一天，最远 [HerAlarmDays.MAX_DAYS_AHEAD] 天后；
 * - 同一天（按触发那天算）最多 5 个没触发的；
 * - 凌晨 1 点到 8 点不能定（那是她睡觉的时间）；
 * - `note` 是写给「到时候的她」的备忘，不是写给对方的；
 * - 成功时告诉她定在了哪天（今天 / 明天 / 周X + 时间）；超限或违规返回中文原因，不抛异常。
 *
 * 存进 `her_alarm` 表；定时任务页「今天的时间线」显示今天的，「接下来」分组显示以后的（都只读）。
 */
class WhaleAlarmTools(
    private val herAlarms: HerAlarmRepository,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val now: () -> Long = { System.currentTimeMillis() },
    /** 定完之后让调度层立刻注册这个一次性闹钟（不注册的话要等下次启动/开机才生效）。 */
    private val onScheduled: suspend () -> Unit = {},
) {

    fun create(): List<Tool> = listOf(setAlarmTool())

    private fun setAlarmTool() = Tool(
        name = "set_alarm",
        description = "给自己定一个一次性的闹钟。time 是几点几分，格式 21:30；" +
            "date 是哪一天，格式 2026-10-24，不填就是今天（这个点已经过了就是明天），最远定到 7 天以后；" +
            "note 是写给到时候的你自己的备忘，不是写给对方的，比如「（自己定的闹钟：去刷刷热榜，看到好玩的再决定要不要告诉对方。）」。" +
            "凌晨 1 点到 8 点不定，一天最多 5 个。",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put(
                        "time",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("几点几分，24 小时制，例如 21:30"))
                        },
                    )
                    put(
                        "date",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("哪一天，格式 2026-10-24；不填就是今天，最远 7 天以后"))
                        },
                    )
                    put(
                        "note",
                        buildJsonObject {
                            put("type", JsonPrimitive("string"))
                            put("description", JsonPrimitive("写给到时候的你自己的备忘，不是写给对方的"))
                        },
                    )
                },
                required = listOf("time", "note"),
            )
        },
        execute = { args ->
            listOf(UIMessagePart.Text(setAlarm(args.jsonObject)))
        },
    )

    internal suspend fun setAlarm(args: JsonObject): String {
        val timeText = args["time"]?.jsonPrimitive?.content?.trim().orEmpty()
        val dateText = args["date"]?.jsonPrimitive?.content?.trim().orEmpty()
        val note = args["note"]?.jsonPrimitive?.content?.trim()
            ?.take(MAX_NOTE_CHARS)
            ?.takeIf { it.isNotEmpty() }
            ?: return errorJson("note 不能为空，写一句到时候给你自己的备忘")

        val time = parseTime(timeText) ?: return errorJson("时间格式是 21:30 这样")
        if (inSleepWindow(time)) {
            return errorJson("凌晨 1 点到 8 点是你睡觉的时间，别定这个点的闹钟")
        }

        val current = now()
        val today = java.time.Instant.ofEpochMilli(current).atZone(zone).toLocalDate()
        val fireAt = if (dateText.isEmpty()) {
            resolveFireAt(time, today, current)
        } else {
            val date = parseDate(dateText) ?: return errorJson("日期格式是 2026-10-24 这样")
            if (date.isBefore(today)) return errorJson("这一天已经过去了")
            val lastDay = today.plusDays(HerAlarmDays.MAX_DAYS_AHEAD.toLong())
            if (date.isAfter(lastDay)) {
                return errorJson("最远只能定到 ${HerAlarmDays.MAX_DAYS_AHEAD} 天以后（${lastDay}）")
            }
            val at = millisOf(date, time)
            if (at <= current) return errorJson("今天的这个点已经过了，换个时间")
            at
        }

        val fireDate = java.time.Instant.ofEpochMilli(fireAt).atZone(zone).toLocalDate()
        if (herAlarms.pendingOn(fireDate).size >= MAX_PER_DAY) {
            return errorJson(
                if (fireDate == today) "今天已经定了 $MAX_PER_DAY 个闹钟了，先过完今天吧"
                else "${HerAlarmDays.dayLabel(fireDate, today)}已经定了 $MAX_PER_DAY 个闹钟了，换一天吧",
            )
        }

        val alarm = herAlarms.add(fireAt = fireAt, note = note)
        runCatchingCancellable { onScheduled() }
        return buildJsonObject {
            put("saved", JsonPrimitive(true))
            put("id", JsonPrimitive(alarm.id))
            // 告诉她到底定在了哪天：不填 date 时这个点已过会顺延到明天
            put("when", JsonPrimitive(HerAlarmDays.label(fireAt, current, zone)))
            put("date", JsonPrimitive(fireDate.toString()))
            put("time", JsonPrimitive(time.format(TIME_FORMAT)))
        }.toString()
    }

    /** 定的是「今天的这个点」，如果已经过了就顺延到明天。 */
    private fun resolveFireAt(time: LocalTime, today: LocalDate, current: Long): Long {
        val todayAt = millisOf(today, time)
        return if (todayAt > current) todayAt else millisOf(today.plusDays(1), time)
    }

    private fun millisOf(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    private fun parseTime(text: String): LocalTime? {
        val match = TIME_REGEX.find(text) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return LocalTime.of(hour, minute)
    }

    private fun parseDate(text: String): LocalDate? =
        if (DATE_REGEX.matches(text)) runCatching { LocalDate.parse(text) }.getOrNull() else null

    /** 凌晨 1:00 到 8:00 是她睡觉的时间。 */
    private fun inSleepWindow(time: LocalTime): Boolean =
        !time.isBefore(SLEEP_START) && time.isBefore(SLEEP_END)

    private fun errorJson(message: String): String =
        buildJsonObject { put("error", JsonPrimitive(message)) }.toString()

    private companion object {
        const val MAX_PER_DAY = 5
        const val MAX_NOTE_CHARS = 200
        val SLEEP_START: LocalTime = LocalTime.of(1, 0)
        val SLEEP_END: LocalTime = LocalTime.of(8, 0)
        val TIME_REGEX = Regex("""^(\d{1,2})[:：](\d{2})$""")
        val DATE_REGEX = Regex("""^\d{4}-\d{2}-\d{2}$""")
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
