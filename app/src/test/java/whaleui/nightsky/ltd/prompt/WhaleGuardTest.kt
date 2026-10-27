package whaleui.nightsky.ltd.prompt

import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WhaleGuard] 的单元测试。
 *
 * 提示词模板直接从工程里的 `src/main/assets/whale/prompts/` 读（Gradle 单测的工作目录是 app 模块），
 * 保证测的就是生产会读到的那份文本；时间全部用固定时钟（[ZonedDateTime.of]），不读系统时钟。
 */
class WhaleGuardTest {

    /** 测试用 loader：和 [WhaleGuard.assetsPromptReader] 读同一批 assets 文件。 */
    private val readPrompt: (String) -> String = { name ->
        File("src/main/assets/whale/prompts/$name").readText()
    }

    /** 读模板并去掉结尾换行，和 [WhaleGuard] 内部的处理一致。 */
    private fun prompt(name: String): String = readPrompt(name).trimEnd('\r', '\n')

    private fun zoned(date: String, time: String = "21:30"): ZonedDateTime =
        ZonedDateTime.of(LocalDate.parse(date), LocalTime.parse(time), ZoneId.of("Asia/Shanghai"))

    /** 2026-10-26 是周一。 */
    private val monday = zoned("2026-10-26")

    /** 2026-10-25 是周日。 */
    private val sunday = zoned("2026-10-25")

    /** 2026-10-24 是周六。 */
    private val saturday = zoned("2026-10-24")

    // ---------------- _gap_text ----------------

    @Test
    fun `gap text covers every hermes branch`() {
        assertEquals("不到 1 分钟", WhaleGuard.gapText(0))
        assertEquals("不到 1 分钟", WhaleGuard.gapText(59))
        assertEquals("1 分钟", WhaleGuard.gapText(60))
        assertEquals("1 分钟", WhaleGuard.gapText(119))
        assertEquals("59 分钟", WhaleGuard.gapText(59L * 60))
        assertEquals("1 小时", WhaleGuard.gapText(60L * 60))
        assertEquals("1 小时 30 分钟", WhaleGuard.gapText(90L * 60))
        assertEquals("2 小时", WhaleGuard.gapText(2L * 60 * 60))
        assertEquals("2 小时 15 分钟", WhaleGuard.gapText((2L * 60 + 15) * 60))
        assertEquals("3 小时", WhaleGuard.gapText(3L * 60 * 60))
        // Hermes 里 h >= 3 时不再补分钟，3 小时 5 分也显示「3 小时」
        assertEquals("3 小时", WhaleGuard.gapText((3L * 60 + 5) * 60))
        assertEquals("1 天", WhaleGuard.gapText(25L * 60 * 60))
        assertEquals("2 天", WhaleGuard.gapText(48L * 60 * 60))
        assertEquals("7 天", WhaleGuard.gapText(7L * 24 * 60 * 60))
    }

    // ---------------- 报时 ----------------

    @Test
    fun `weekday uses monday first mapping`() {
        assertEquals("[现在 2026-10-26 21:30 周一]", WhaleGuard.timeLine(monday, null, false, readPrompt))
        assertEquals("[现在 2026-10-24 21:30 周六]", WhaleGuard.timeLine(saturday, null, false, readPrompt))
        assertEquals("[现在 2026-10-25 21:30 周日]", WhaleGuard.timeLine(sunday, null, false, readPrompt))
    }

    @Test
    fun `normal turn time line has the gap to his last message`() {
        assertEquals(
            "[现在 2026-10-26 21:30 周一，距对方上一条消息 3 分钟]",
            WhaleGuard.timeLine(monday, monday.minusMinutes(3), isAlarm = false, readPrompt = readPrompt),
        )
        assertEquals(
            "[现在 2026-10-26 21:30 周一，距对方上一条消息 2 小时 15 分钟]",
            WhaleGuard.timeLine(
                monday,
                monday.minusHours(2).minusMinutes(15),
                isAlarm = false,
                readPrompt = readPrompt,
            ),
        )
        assertEquals(
            "[现在 2026-10-26 21:30 周一，距对方上一条消息 不到 1 分钟]",
            WhaleGuard.timeLine(monday, monday.minusSeconds(59), isAlarm = false, readPrompt = readPrompt),
        )
    }

    @Test
    fun `normal turn time line omits the gap when there is no last user message`() {
        assertEquals("[现在 2026-10-26 21:30 周一]", WhaleGuard.timeLine(monday, null, isAlarm = false, readPrompt = readPrompt))
    }

    @Test
    fun `alarm turn time line uses the alarm gap wording and appends cron frame`() {
        assertEquals(
            "[现在 2026-10-26 21:30 周一，对方上一条消息是 2 小时前]\n" + prompt("cron_frame.txt"),
            WhaleGuard.timeLine(monday, monday.minusHours(2), isAlarm = true, readPrompt = readPrompt),
        )
        assertEquals(
            "[现在 2026-10-26 21:30 周一，对方上一条消息是 1 天前]\n" + prompt("cron_frame.txt"),
            WhaleGuard.timeLine(monday, monday.minusHours(25), isAlarm = true, readPrompt = readPrompt),
        )
        // 没有上一条消息时，闹钟轮只有报时 + CRON_FRAME，没有间隔那一段
        assertEquals(
            "[现在 2026-10-26 21:30 周一]\n" + prompt("cron_frame.txt"),
            WhaleGuard.timeLine(monday, null, isAlarm = true, readPrompt = readPrompt),
        )
    }

    // ---------------- 语音 / 防复读 ----------------

    @Test
    fun `voice line fills in todays count`() {
        assertEquals(prompt("voice_line.txt").replace("{n}", "3"), WhaleGuard.voiceLine(3, readPrompt))
        assertEquals(prompt("voice_line.txt").replace("{n}", "0"), WhaleGuard.voiceLine(0, readPrompt))
        assertTrue(WhaleGuard.voiceLine(7, readPrompt).contains("7 条"))
    }

    @Test
    fun `anti repeat line joins openings with the full width bar`() {
        assertEquals(
            prompt("anti_repeat.txt").replace("{openings}", "开头甲 ｜ 开头乙"),
            WhaleGuard.antiRepeatLine(listOf("开头甲", "开头乙"), readPrompt),
        )
    }

    @Test
    fun `anti repeat line is null when there is no opening`() {
        assertNull(WhaleGuard.antiRepeatLine(emptyList(), readPrompt))
        assertNull(WhaleGuard.antiRepeatLine(listOf("", "   "), readPrompt))
    }

    @Test
    fun `recent openings go from newest to oldest`() {
        assertEquals(
            listOf("最新", "中间", "最旧"),
            WhaleGuard.recentOpenings(listOf("最旧", "中间", "最新")),
        )
    }

    @Test
    fun `recent openings skip silent and blank replies and dedupe`() {
        val replies = listOf(
            "[SILENT]",
            "[SILENT] 今天不想说话",
            "   ",
            "",
            "同一句开头\n第二行不算",
            "别的开头",
            "同一句开头",
        )

        assertEquals(listOf("同一句开头", "别的开头"), WhaleGuard.recentOpenings(replies))
    }

    @Test
    fun `recent openings honour max and the default is four`() {
        val replies = listOf("一", "二", "三", "四", "五", "六")

        assertEquals(listOf("六", "五"), WhaleGuard.recentOpenings(replies, max = 2))
        assertEquals(4, WhaleGuard.recentOpenings(replies).size)
        assertTrue(WhaleGuard.recentOpenings(replies, max = 0).isEmpty())
    }

    @Test
    fun `recent openings take the first line trimmed and at most twenty characters`() {
        assertEquals(listOf("第一行"), WhaleGuard.recentOpenings(listOf("  第一行  \n第二行")))
        assertEquals(listOf("甲".repeat(20)), WhaleGuard.recentOpenings(listOf("甲".repeat(30) + "\n尾巴")))
    }

    @Test
    fun `recent openings count characters not utf16 units`() {
        // Python 的 s[:20] 数的是码点，emoji 不能被切成半个
        assertEquals(listOf("😄".repeat(20)), WhaleGuard.recentOpenings(listOf("😄".repeat(25))))
    }

    // ---------------- 普通轮 / 闹钟轮拼接 ----------------

    @Test
    fun `normal turn injection follows the hermes order`() {
        val stamp = "[现在 2026-10-26 21:30 周一，距对方上一条消息 3 分钟]"
        val reminder = prompt("reminder.txt")
        val voice = prompt("voice_line.txt").replace("{n}", "2")
        val antiRepeat = prompt("anti_repeat.txt").replace("{openings}", "开头甲 ｜ 开头乙")

        val injection = WhaleGuard.buildNormalTurnInjection(
            now = monday,
            lastUserAt = monday.minusMinutes(3),
            voiceCountToday = 2,
            recentOpenings = listOf("开头甲", "开头乙"),
            readPrompt = readPrompt,
        )

        assertEquals(listOf(stamp, reminder, voice, antiRepeat).joinToString("\n"), injection)
        // 顺序：报时 → REMINDER → 语音提醒 → 防复读
        val positions = listOf(stamp, reminder, voice, antiRepeat).map { injection.indexOf(it) }
        assertTrue(positions.all { it >= 0 })
        assertEquals(positions.sorted(), positions)
        // 普通轮不带 CRON_FRAME
        assertFalse(injection.contains(prompt("cron_frame.txt")))
    }

    @Test
    fun `normal turn injection omits gap and anti repeat when data is missing`() {
        val injection = WhaleGuard.buildNormalTurnInjection(
            now = monday,
            lastUserAt = null,
            voiceCountToday = 0,
            recentOpenings = emptyList(),
            readPrompt = readPrompt,
        )

        assertEquals(
            listOf(
                "[现在 2026-10-26 21:30 周一]",
                prompt("reminder.txt"),
                prompt("voice_line.txt").replace("{n}", "0"),
            ).joinToString("\n"),
            injection,
        )
        assertFalse(injection.contains("距对方上一条消息"))
        assertFalse(injection.contains(prompt("anti_repeat.txt").substringBefore("{openings}")))
    }

    @Test
    fun `alarm turn injection has no reminder and no anti repeat`() {
        val stamp = "[现在 2026-10-26 21:30 周一，对方上一条消息是 1 天前]\n" + prompt("cron_frame.txt")
        val voice = prompt("voice_line.txt").replace("{n}", "1")

        val injection = WhaleGuard.buildAlarmTurnInjection(
            now = monday,
            lastUserAt = monday.minusHours(25),
            voiceCountToday = 1,
            readPrompt = readPrompt,
        )

        assertEquals(listOf(stamp, voice).joinToString("\n"), injection)
        assertFalse(injection.contains(prompt("reminder.txt")))
        assertFalse(injection.contains(prompt("anti_repeat.txt").substringBefore("{openings}")))
        assertTrue(injection.contains(prompt("cron_frame.txt")))
    }

    @Test
    fun `injection can use a fixed whale turn clock`() {
        val clock = WhaleTurnClock { monday }

        assertEquals(
            WhaleGuard.buildNormalTurnInjection(monday, monday.minusMinutes(3), 2, listOf("开头甲"), readPrompt),
            WhaleGuard.buildNormalTurnInjection(clock, monday.minusMinutes(3), 2, listOf("开头甲"), readPrompt),
        )
        assertEquals(
            WhaleGuard.buildAlarmTurnInjection(monday, null, 0, readPrompt),
            WhaleGuard.buildAlarmTurnInjection(clock, null, 0, readPrompt),
        )
    }

    // ---------------- 表情提醒（round5 3.4） ----------------

    @Test
    fun `sticker line fills in the recent count`() {
        assertEquals(prompt("sticker_line.txt").replace("{n}", "2"), WhaleGuard.stickerLine(2, readPrompt))
        assertTrue(WhaleGuard.stickerLine(0, readPrompt).contains("0 张"))
        // 不鼓励也不压制（提示词第 7 批），全角标点
        assertEquals("（这条也可以配张表情包，最近 10 轮发过 0 张。）", WhaleGuard.stickerLine(0, readPrompt))
        assertFalse(WhaleGuard.stickerLine(0, readPrompt).contains("别刷屏"))
        assertEquals("（这条也可以发成语音，今天已经发了 3 条。）", WhaleGuard.voiceLine(3, readPrompt))
    }

    @Test
    fun `sticker line sits after the voice line and before anti repeat`() {
        val sticker = WhaleGuard.stickerLine(3, readPrompt)
        val injection = WhaleGuard.buildNormalTurnInjection(
            now = monday,
            lastUserAt = monday.minusMinutes(3),
            voiceCountToday = 1,
            recentOpenings = listOf("开头甲"),
            readPrompt = readPrompt,
            stickerLine = sticker,
        )
        val voiceAt = injection.indexOf(prompt("voice_line.txt").replace("{n}", "1"))
        val stickerAt = injection.indexOf(sticker)
        val antiAt = injection.indexOf(prompt("anti_repeat.txt").replace("{openings}", "开头甲"))
        assertTrue("voice < sticker", voiceAt in 0 until stickerAt)
        assertTrue("sticker < anti repeat", stickerAt in 0 until antiAt)
    }

    @Test
    fun `no sticker line means no sticker reminder in the injection`() {
        val injection = WhaleGuard.buildNormalTurnInjection(
            now = monday,
            lastUserAt = null,
            voiceCountToday = 0,
            recentOpenings = emptyList(),
            readPrompt = readPrompt,
        )
        assertFalse(injection.contains("表情包"))
    }

    @Test
    fun `alarm turns never get the sticker reminder`() {
        val injection = WhaleGuard.buildAlarmTurnInjection(
            now = monday,
            lastUserAt = null,
            voiceCountToday = 0,
            readPrompt = readPrompt,
        )
        assertFalse(injection.contains("表情包"))
    }
}
