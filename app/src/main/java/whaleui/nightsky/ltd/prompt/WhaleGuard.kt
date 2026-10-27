package whaleui.nightsky.ltd.prompt

import android.content.Context
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 每轮注入的内容（任务书 4.3）：报时、REMINDER、语音提醒、防复读、闹钟轮。
 *
 * 逻辑逐字照抄 Hermes `plugins/persona_guard/__init__.py` 的 `_gap_text` / `time_line` /
 * `voice_line` / `_recent_openings` / `on_pre_llm_call`。提示词正文不写死在代码里，
 * 全部来自 `assets/whale/prompts/` 目录下的 `*.txt`，通过可注入的 [readPrompt] 读取（测试直接读工程里
 * 的同一份文件，保证和生产一致）。
 *
 * 拼接顺序（和 Hermes 一致）：
 * - 普通轮：[timeLine] → `reminder.txt` → [voiceLine] → [antiRepeatLine]（有最近开头才加）；
 * - 闹钟轮：[timeLine]（自带 `cron_frame.txt`，换行接在后面）→ [voiceLine]，
 *   **不加** REMINDER，**不加**防复读。
 *
 * 纯函数：只依赖入参（`now` 由调用方用 [WhaleTurnClock] 取，函数里绝不读系统时钟）。
 */
object WhaleGuard {

    /** 防复读最多取她最近几句开头，和 Hermes 的 `MAX_RECENT` 一致。 */
    const val DEFAULT_MAX_OPENINGS: Int = 4

    /** 防复读里两个开头之间的分隔符，和 Hermes 的 `" ｜ "` 一致。 */
    const val OPENING_SEPARATOR: String = " ｜ "

    /** 一条回复取开头时最多几个字（码点）。 */
    private const val OPENING_CHARS = 20

    /** 静默标记；以它开头的回复不参与防复读。 */
    private const val SILENT_MARKER = "[SILENT]"

    /** `WEEK = "一二三四五六日"`，Java 的 `DayOfWeek.value` 是周一=1。 */
    private const val WEEKDAYS = "一二三四五六日"

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd", Locale.ROOT)
    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    // 提示词文件都在 app/src/main/assets/whale/prompts/ 下（名字带 .txt）。
    private const val PROMPT_TIME_HEAD = "time_head.txt"
    private const val PROMPT_GAP_NORMAL = "gap_normal.txt"
    private const val PROMPT_GAP_ALARM = "gap_alarm.txt"
    private const val PROMPT_CRON_FRAME = "cron_frame.txt"
    private const val PROMPT_REMINDER = "reminder.txt"
    private const val PROMPT_VOICE_LINE = "voice_line.txt"
    private const val PROMPT_ANTI_REPEAT = "anti_repeat.txt"
    private const val PROMPT_STICKER_LINE = "sticker_line.txt"

    /**
     * 生产用 loader：从 `assets/whale/prompts/<name>` 读文本（[name] 带 `.txt`）。
     *
     * 读不到时返回空串（和 `WhaleSystemPrompt` 的处理一致，不让一次资源问题炸掉整轮请求）。
     */
    fun assetsPromptReader(context: Context): (String) -> String = { name ->
        runCatching {
            context.assets.open("whale/prompts/$name").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    /**
     * 间隔文案，逐字照抄 Hermes `_gap_text`：
     *
     * - 不到 60 秒 → `不到 1 分钟`；
     * - 不到 60 分钟 → `{m} 分钟`；
     * - 不到 24 小时 → `{h} 小时`，只有 `h < 3` 且分钟数不为 0 时才补 ` {m%60} 分钟`
     *   （所以 3 小时整是「3 小时」，3 小时 5 分也是「3 小时」，2 小时 15 分是「2 小时 15 分钟」）；
     * - 再往上 → `{h/24} 天`（25 小时是「1 天」）。
     */
    fun gapText(seconds: Long): String {
        if (seconds < 60) return "不到 1 分钟"
        val minutes = seconds / 60
        if (minutes < 60) return "$minutes 分钟"
        val hours = minutes / 60
        if (hours < 24) {
            val restMinutes = minutes % 60
            return if (hours < 3 && restMinutes != 0L) {
                "$hours 小时 $restMinutes 分钟"
            } else {
                "$hours 小时"
            }
        }
        return "${hours / 24} 天"
    }

    /**
     * 报时那一行。
     *
     * 普通轮：`[现在 yyyy-MM-dd HH:mm 周X` +（有上一条 user 消息时）`，距他上一条消息 {gap}` + `]`；
     * 闹钟轮：`[现在 ...` +（有上一条时）`，他上一条消息是 {gap}前` + `]`，再换行接 `cron_frame.txt`。
     *
     * @param lastUserAt 历史里上一条 user 消息的时间；没有就传 null（整段间隔省略）
     * @param isAlarm 闹钟轮（Hermes 的 `platform == "cron"`）为 true
     */
    fun timeLine(
        now: ZonedDateTime,
        lastUserAt: ZonedDateTime?,
        isAlarm: Boolean,
        readPrompt: (String) -> String,
    ): String {
        val head = load(readPrompt, PROMPT_TIME_HEAD)
            .replace("{date}", DATE_FORMAT.format(now))
            .replace("{time}", TIME_FORMAT.format(now))
            .replace("{weekday}", WEEKDAYS[now.dayOfWeek.value - 1].toString())

        val gapPart = if (lastUserAt == null) {
            ""
        } else {
            val gap = gapText(Duration.between(lastUserAt, now).seconds)
            val template = if (isAlarm) PROMPT_GAP_ALARM else PROMPT_GAP_NORMAL
            load(readPrompt, template).replace("{gap}", gap)
        }

        val stamp = head + gapPart + "]"
        if (!isAlarm) return stamp
        return stamp + "\n" + load(readPrompt, PROMPT_CRON_FRAME)
    }

    /** 语音提醒：把 `voice_line.txt` 里的 `{n}` 换成今天已经发出的语音条数。 */
    fun voiceLine(voiceCountToday: Int, readPrompt: (String) -> String): String =
        load(readPrompt, PROMPT_VOICE_LINE).replace("{n}", voiceCountToday.toString())

    /**
     * 表情提醒（任务书 3.4 第 3 条）：把 `sticker_line.txt` 里的 `{n}` 换成最近 10 轮她发的表情数。
     *
     * 只在普通轮、且**有可用表情**时由调用方决定加不加（[WhaleGuardTransformer]）。
     */
    fun stickerLine(stickerCountRecent: Int, readPrompt: (String) -> String): String =
        load(readPrompt, PROMPT_STICKER_LINE).replace("{n}", stickerCountRecent.toString())

    /**
     * 防复读那一行：`anti_repeat.txt` 的 `{openings}` 换成 `" ｜ "` 连接的开头列表。
     *
     * [recentOpenings] 为空（或全是空白）时返回 null，调用方据此不追加这一段。
     */
    fun antiRepeatLine(recentOpenings: List<String>, readPrompt: (String) -> String): String? {
        val openings = recentOpenings.filter { it.isNotBlank() }
        if (openings.isEmpty()) return null
        return load(readPrompt, PROMPT_ANTI_REPEAT)
            .replace("{openings}", openings.joinToString(OPENING_SEPARATOR))
    }

    /**
     * 取她最近几条回复的开头，逐字照抄 Hermes `_recent_openings`：
     * 从后往前、跳过空回复和 `[SILENT]` 开头的回复、取首行 trim 后前 20 个字（码点）、
     * 去重（同开头只留最近一次出现的那条顺序位置），最多 [max] 条。
     *
     * @param herReplies 她发过的回复文本，按时间从旧到新
     */
    fun recentOpenings(herReplies: List<String>, max: Int = DEFAULT_MAX_OPENINGS): List<String> {
        if (max <= 0) return emptyList()
        val openings = mutableListOf<String>()
        for (reply in herReplies.asReversed()) {
            val text = reply.trim()
            if (text.isEmpty() || text.startsWith(SILENT_MARKER)) continue
            val firstLine = text.lineSequence().firstOrNull()?.trim().orEmpty()
            if (firstLine.isEmpty()) continue
            val opening = takeCodePoints(firstLine, OPENING_CHARS)
            if (opening !in openings) openings.add(opening)
            if (openings.size >= max) break
        }
        return openings
    }

    /**
     * 普通轮注入：报时 → REMINDER → 语音提醒 → 防复读（顺序同 Hermes `on_pre_llm_call`）。
     *
     * @param recentOpenings 一般由 [recentOpenings] 从她的回复里算出来；空则不加防复读
     */
    fun buildNormalTurnInjection(
        now: ZonedDateTime,
        lastUserAt: ZonedDateTime?,
        voiceCountToday: Int,
        recentOpenings: List<String>,
        readPrompt: (String) -> String,
        /** 表情提醒那一行；没有可用表情时传 null（任务书 3.4）。 */
        stickerLine: String? = null,
    ): String {
        val lines = mutableListOf(
            timeLine(now = now, lastUserAt = lastUserAt, isAlarm = false, readPrompt = readPrompt),
            load(readPrompt, PROMPT_REMINDER),
            voiceLine(voiceCountToday, readPrompt),
        )
        if (!stickerLine.isNullOrBlank()) lines += stickerLine
        antiRepeatLine(recentOpenings, readPrompt)?.let(lines::add)
        return lines.joinToString("\n")
    }

    /** [buildNormalTurnInjection] 的时钟版本：`now = clock.now()`。 */
    fun buildNormalTurnInjection(
        clock: WhaleTurnClock,
        lastUserAt: ZonedDateTime?,
        voiceCountToday: Int,
        recentOpenings: List<String>,
        readPrompt: (String) -> String,
        stickerLine: String? = null,
    ): String = buildNormalTurnInjection(
        now = clock.now(),
        lastUserAt = lastUserAt,
        voiceCountToday = voiceCountToday,
        recentOpenings = recentOpenings,
        readPrompt = readPrompt,
        stickerLine = stickerLine,
    )

    /**
     * 闹钟轮注入：报时（含 `cron_frame.txt`）→ 语音提醒。
     *
     * **不加** REMINDER，**不加**防复读（Hermes 闹钟轮没有「接住他这句话」的语境）。
     */
    fun buildAlarmTurnInjection(
        now: ZonedDateTime,
        lastUserAt: ZonedDateTime?,
        voiceCountToday: Int,
        readPrompt: (String) -> String,
    ): String = listOf(
        timeLine(now = now, lastUserAt = lastUserAt, isAlarm = true, readPrompt = readPrompt),
        voiceLine(voiceCountToday, readPrompt),
    ).joinToString("\n")

    /** [buildAlarmTurnInjection] 的时钟版本：`now = clock.now()`。 */
    fun buildAlarmTurnInjection(
        clock: WhaleTurnClock,
        lastUserAt: ZonedDateTime?,
        voiceCountToday: Int,
        readPrompt: (String) -> String,
    ): String = buildAlarmTurnInjection(
        now = clock.now(),
        lastUserAt = lastUserAt,
        voiceCountToday = voiceCountToday,
        readPrompt = readPrompt,
    )

    /** 读模板并去掉可能存在的结尾换行（assets 里目前没有，留着防御）。 */
    private fun load(readPrompt: (String) -> String, name: String): String =
        readPrompt(name).trimEnd('\r', '\n')

    /** 按「码点」取前 [count] 个字，和 Python 的 `s[:20]` 行为一致（emoji 不会被切一半）。 */
    private fun takeCodePoints(text: String, count: Int): String {
        if (text.length <= count) return text
        var end = 0
        var taken = 0
        while (end < text.length && taken < count) {
            end += Character.charCount(text.codePointAt(end))
            taken++
        }
        return text.substring(0, end)
    }
}
