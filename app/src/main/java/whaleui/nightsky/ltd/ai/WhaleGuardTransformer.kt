package whaleui.nightsky.ltd.ai

import java.time.ZoneId
import java.time.ZonedDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import whaleui.nightsky.ltd.prompt.WhaleGuard
import whaleui.nightsky.ltd.prompt.WhaleTurnClock
import whaleui.nightsky.ltd.sticker.StickerAvailability
import whaleui.nightsky.ltd.sticker.stickerPartCount
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.schedule.SeedTaskIds
import whaleui.nightsky.ltd.whale.WhaleAlarmTurn

/**
 * 每轮注入（任务书 4.3，`InputMessageTransformer`，放在原生列表**最前**）。
 *
 * **只改发给模型的请求，不落库**。两种轮次：
 *
 * 1. 普通轮：在最后一条 user 消息的文字末尾空一行，追加
 *    报时 → REMINDER → 语音提醒 → 防复读。
 * 2. 闹钟轮（`TransformerContext.whaleAlarm` 不为 null，随这一轮的请求传进来）：在请求末尾追加一条**合成 user 消息**
 *    （`isSynthetic = true`，不落库），内容依次是
 *    报时（间隔写成「他上一条消息是 N 前」）→ CRON_FRAME → 闹钟说明 → 语音提醒；
 *    **不加** REMINDER，**不加**防复读。
 *
 * 报时、语音计数、防复读的素材全部从 `messages` 里现算（任务书要求「直接从对话数据统计，不另存状态」）：
 * - 「对方上一条消息」= 最后这批连发之前的最后一条 user 的 createdAt（闹钟轮：最后一条 user）；
 * - 「今天她发了几条语音」= 今天（设备本地时区）assistant 消息里 Audio 部件的数量；
 * - 防复读 = 她最近 4 条非静默回复的首行前 20 字。
 */
class WhaleGuardTransformer(
    private val readPrompt: (String) -> String,
    private val clock: WhaleTurnClock = WhaleTurnClock.System,
    /** 表情库可用性（round5）：没有可用表情时不加表情提醒（任务书 3.4 第 3 条）。 */
    private val stickerAvailability: StickerAvailability? = null,
) : InputMessageTransformer {

    private companion object {
        /** 睡前轮追加的记忆整理提示（逐字，任务书 4.1） */
        const val MEMORY_TIDY_PROMPT = "memory_tidy_night.txt"

        /** 表情提醒里的「最近 N 轮」（任务书 3.4）。 */
        const val STICKER_RECENT_TURNS = 10
    }

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!WhaleGate.isWhaleAssistant(ctx.assistant.id)) return messages
        if (messages.isEmpty()) return messages

        // 整轮共用一个报时时间（W-026）：工具执行跨过整分钟时，第 2 步的注入也和第 1 步一样
        val now = ctx.turnNow ?: clock.now()
        val alarm = ctx.whaleAlarm
        val lastUserAt = previousUserMessage(messages, isAlarm = alarm != null)?.toZonedDateTime()
        val voiceCount = countVoiceToday(messages, now)

        if (alarm != null) {
            val injection = buildAlarmInjection(now, lastUserAt, voiceCount, alarm)
            if (injection.isBlank()) return messages
            val synthetic = UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(injection)),
            ).copy(isSynthetic = true)
            return messages + synthetic
        }

        val herReplies = messages.filter { it.role == MessageRole.ASSISTANT }
            .mapNotNull { it.textOf() }
        val openings = WhaleGuard.recentOpenings(herReplies)
        // 表情提醒（任务书 3.4）：只在有可用表情时加，{n} 从对话数据里现算
        val stickerLine = stickerAvailability?.let { availability ->
            if (!availability.get().available) {
                null
            } else {
                WhaleGuard.stickerLine(
                    stickerCountRecent = countStickersInRecentTurns(messages, STICKER_RECENT_TURNS),
                    readPrompt = readPrompt,
                )
            }
        }
        val injection = WhaleGuard.buildNormalTurnInjection(
            now = now,
            lastUserAt = lastUserAt,
            voiceCountToday = voiceCount,
            recentOpenings = openings,
            readPrompt = readPrompt,
            stickerLine = stickerLine,
        )
        if (injection.isBlank()) return messages

        val lastIndex = messages.lastIndex
        if (messages[lastIndex].role != MessageRole.USER) return messages
        return messages.mapIndexed { index, message ->
            if (index == lastIndex) message.appendText(injection) else message
        }
    }

    /**
     * 报时里「对方上一条消息」指的那条 user 消息。
     *
     * - 普通轮：最后一段连发的 user 消息是这一批（这时还没合并），要从**这一批之前**那条 user 消息算起，
     *   否则连发时总是「不到 1 分钟」（提示词第 5 批）；
     * - 闹钟轮：历史里最后一条 user 消息（闹钟轮没有「这一批」，最后一条可能就是对方还没被回的话）。
     */
    private fun previousUserMessage(messages: List<UIMessage>, isAlarm: Boolean): UIMessage? {
        if (isAlarm) return messages.lastOrNull { it.role == MessageRole.USER }
        var burstStart = messages.size
        while (burstStart > 0 && messages[burstStart - 1].role == MessageRole.USER) burstStart--
        return messages.subList(0, burstStart).lastOrNull { it.role == MessageRole.USER }
    }

    /**
     * 最近 [turns] 轮里她发了几张表情（任务书 3.4 第 3 条）。
     *
     * 轮次边界和 `WhaleHistoryLimiter` 一致：一条 user 消息 + 它后面所有非 user 消息算一轮；
     * 连发的多条 user 消息（round5 3.7）算同一轮。切开的气泡（每条一个节点）自然算在同一轮里。
     */
    private fun countStickersInRecentTurns(messages: List<UIMessage>, turns: Int): Int {
        val turnStarts = messages.indices.filter { index ->
            messages[index].role == MessageRole.USER &&
                (index == 0 || messages[index - 1].role != MessageRole.USER)
        }
        if (turnStarts.isEmpty()) return 0
        val fromTurn = (turnStarts.size - turns).coerceAtLeast(0)
        val fromIndex = turnStarts[fromTurn]
        return messages.subList(fromIndex, messages.size)
            .filter { it.role == MessageRole.ASSISTANT }
            .sumOf { it.stickerPartCount() }
    }

    /**
     * 闹钟轮的合成消息：报时（含 CRON_FRAME）→ 闹钟说明 → 语音提醒。
     * 顺序按任务书 4.3 来，所以这里没有直接复用 `buildAlarmTurnInjection`
     * （那个把语音提醒紧跟在 CRON_FRAME 后面）。
     */
    private fun buildAlarmInjection(
        now: ZonedDateTime,
        lastUserAt: ZonedDateTime?,
        voiceCount: Int,
        alarm: WhaleAlarmTurn,
    ): String {
        val stamp = WhaleGuard.timeLine(now, lastUserAt, isAlarm = true, readPrompt = readPrompt)
        val voice = WhaleGuard.voiceLine(voiceCount, readPrompt)
        val note = alarm.note.trim()
        // 睡前那一轮（按种子任务的稳定 id 判断，不匹配标题）追加「顺手理一理记忆」这段话（任务书 4.1）
        val tidy = if (alarm.taskId == SeedTaskIds.NIGHT) {
            readPrompt(MEMORY_TIDY_PROMPT).trim()
        } else {
            ""
        }
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            runCatching {
                android.util.Log.i(
                    "WhaleDiag",
                    "alarm injection taskId=${alarm.taskId} tidyLen=${tidy.length}",
                )
            }
        }
        return buildString {
            append(stamp)
            if (note.isNotEmpty()) {
                appendLine()
                append(note)
            }
            if (tidy.isNotEmpty()) {
                appendLine()
                append(tidy)
            }
            if (voice.isNotEmpty()) {
                appendLine()
                append(voice)
            }
        }
    }

    /** 今天（设备本地时区）她的语音条数：数 assistant 消息里的 Audio 部件。 */
    private fun countVoiceToday(messages: List<UIMessage>, now: ZonedDateTime): Int {
        val today = now.toLocalDate()
        return messages.asSequence()
            .filter { it.role == MessageRole.ASSISTANT }
            .filter { it.toZonedDateTime().toLocalDate() == today }
            .sumOf { message ->
                // 顶层 Audio + 工具输出里的 Audio（text_to_speech 的结果嵌在 Tool.output 里）
                val top = message.parts.count { it is UIMessagePart.Audio }
                val nested = message.parts.filterIsInstance<UIMessagePart.Tool>()
                    .sumOf { tool -> tool.output.count { it is UIMessagePart.Audio } }
                top + nested
            }
    }
}

// ------------------------------------------------------------------ 小工具

private fun UIMessage.textOf(): String? =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }

/**
 * 「空一行后追加」，并且不要出现三段以上的连续空行。
 *
 * 追加位置必须在**消息末尾**：连发批次里最后一条可能以图片/表情结尾（round5 3.7），
 * 那种情况就新加一个 Text 部件，不能把注入塞进前面那段文字里。
 */
private fun UIMessage.appendText(extra: String): UIMessage {
    val lastTextIndex = parts.indexOfLast { it is UIMessagePart.Text }
    if (lastTextIndex >= 0 && lastTextIndex == parts.lastIndex) {
        val text = parts[lastTextIndex] as UIMessagePart.Text
        val newParts = parts.toMutableList()
        newParts[lastTextIndex] = UIMessagePart.Text(text.text.trimEnd() + "\n\n" + extra, text.metadata)
        return copy(parts = newParts)
    }
    return copy(parts = parts + UIMessagePart.Text(extra))
}

/**
 * `kotlinx.datetime.LocalDateTime` → `java.time.ZonedDateTime`（设备本地时区）。
 * 只用于报时比较，精度到毫秒足够。
 */
private fun UIMessage.toZonedDateTime(): ZonedDateTime {
    val local = createdAt
    return java.time.LocalDateTime.of(
        local.year,
        local.monthNumber,
        local.day,
        local.hour,
        local.minute,
        local.second,
        local.nanosecond,
    ).atZone(ZoneId.systemDefault())
}

