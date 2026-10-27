package whaleui.nightsky.ltd.data

import kotlinx.datetime.LocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.MessageNode

/** 从聊天导出文件里解析出来的一条消息。 */
data class ImportedChatMessage(
    val role: MessageRole,
    val text: String,
    val at: LocalDateTime,
)

/**
 * 解析 App 自己导出的聊天 Markdown（`chat/2026-10-25_miniWhale.md`；旧版导出的 `_小鲸鱼.md` 同样认）：
 *
 * ```markdown
 * # miniWhale
 *
 * ## 2026-10-25
 *
 * - [08:30] 她：今天预报说下午有雨
 * - [13:54] 我：午饭吃了番茄炒蛋
 * ```
 *
 * 一条 `- [HH:mm] 她/我：文本` = 一条消息；`## yyyy-MM-dd` 给当天所有消息提供日期。
 * 导出时正文里的换行已经被压成空格，所以这里一行就是一条消息。
 */
object ChatMarkdownParser {

    private val DateHeading = Regex("""^##\s*(\d{4})-(\d{2})-(\d{2})\s*$""")
    private val MessageLine = Regex("""^\s*-\s*\[(\d{1,2}):(\d{2})\]\s*(她|我)\s*[：:]\s*(.*)$""")

    fun parse(content: String): List<ImportedChatMessage> {
        val text = content.replace("\r\n", "\n").replace('\r', '\n')
        val messages = mutableListOf<ImportedChatMessage>()
        var year = 0
        var month = 0
        var day = 0
        for (line in text.lineSequence()) {
            val heading = DateHeading.find(line.trim())
            if (heading != null) {
                year = heading.groupValues[1].toInt()
                month = heading.groupValues[2].toInt()
                day = heading.groupValues[3].toInt()
                continue
            }
            if (year == 0) continue // 还没看到日期标题
            val match = MessageLine.find(line) ?: continue
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2].toInt()
            val message = match.groupValues[4].trim()
            if (message.isEmpty()) continue
            if (hour !in 0..23 || minute !in 0..59) continue
            if (!isValidDate(year, month, day)) continue
            messages += ImportedChatMessage(
                role = if (match.groupValues[3] == "她") MessageRole.ASSISTANT else MessageRole.USER,
                text = message,
                at = LocalDateTime(year, month, day, hour, minute),
            )
        }
        return messages
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean = runCatching {
        java.time.LocalDate.of(year, month, day)
    }.isSuccess
}

/**
 * 把解析出来的消息并进唯一那个固定对话（纯函数）。
 *
 * - 每条消息 = 一个 [MessageNode]（和 App 正常发送的粒度一致）；
 * - 去重：已有库里（含所有分支）角色 + 文字 + 时间完全相同的消息跳过，重复导入不会翻倍；
 * - 排序：合并后按 `currentMessage.createdAt` 稳定排序（分钟精度下，同一分钟的
 *   user/assistant 必须保持文件里的先后顺序）。
 */
object ChatConversationMerge {

    data class Result(
        val nodes: List<MessageNode>,
        val added: Int,
        val skipped: Int,
    )

    private data class Key(
        val role: MessageRole,
        /** 空白折叠后的正文：导出时换行被压成空格，库里可能还是换行，不折叠就对不上。 */
        val text: String,
        /**
         * 只精确到分钟：聊天导出文件里只有 `[HH:mm]`，没有秒。
         * 用完整时间做去重的话，从导出包导回来会跟库里同一分钟、带秒的旧消息对不上，重复一份。
         */
        val atMinute: String,
    )

    fun merge(
        existing: List<MessageNode>,
        incoming: List<ImportedChatMessage>,
    ): Result {
        if (incoming.isEmpty()) return Result(existing, added = 0, skipped = 0)

        // 已有消息的去重配额：同一条消息可能出现多份（分支节点），按出现次数扣减。
        val remaining = HashMap<Key, Int>()
        existing.forEach { node ->
            node.messages.forEach { message ->
                remaining.merge(keyOf(message), 1, Int::plus)
            }
        }

        val fresh = mutableListOf<MessageNode>()
        var skipped = 0
        incoming.forEach { message ->
            val key = Key(message.role, normalizeText(message.text), message.at.minuteKey())
            val count = remaining[key] ?: 0
            if (count > 0) {
                remaining[key] = count - 1
                skipped += 1
            } else {
                fresh += MessageNode.of(message.toUiMessage())
            }
        }

        val nodes = if (fresh.isEmpty()) {
            existing
        } else {
            (existing + fresh).sortedBy { node -> node.currentMessage.createdAt }
        }
        return Result(nodes = nodes, added = fresh.size, skipped = skipped)
    }

    private fun keyOf(message: UIMessage): Key = Key(
        role = message.role,
        text = normalizeText(
            message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
        ),
        atMinute = message.createdAt.minuteKey(),
    )

    private val Whitespace = Regex("""\s+""")

    /** 导出格式一行一条、换行变空格；比较时两边都折叠空白，避免「同一句话换个空白就算新消息」。 */
    private fun normalizeText(text: String): String = text.trim().replace(Whitespace, " ")

    /** `2026-10-25T14:40:31` → `2026-10-25T14:40`（导出格式本来就没有秒）。 */
    private fun LocalDateTime.minuteKey(): String = toString().take(16)

    private fun ImportedChatMessage.toUiMessage(): UIMessage = UIMessage(
        role = role,
        parts = listOf(UIMessagePart.Text(text)),
        createdAt = at,
        finishedAt = if (role == MessageRole.ASSISTANT) at else null,
    )
}
