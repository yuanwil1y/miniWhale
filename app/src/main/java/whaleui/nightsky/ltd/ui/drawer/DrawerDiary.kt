package whaleui.nightsky.ltd.ui.drawer

import androidx.compose.runtime.Immutable
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import whaleui.nightsky.ltd.domain.memory.DiaryEntry

// ---------------------------------------------------------------------------
// 抽屉底部的「她最近的日记」小卡片（README §5「抽屉空白」④）：只读日记表最新一篇的开头。
// ---------------------------------------------------------------------------

/** 卡片内容。[excerpt] 已去掉 Markdown 标记和日记段首的「[HH:mm]」，界面再按 2 行省略。 */
@Immutable
data class DrawerDiaryPreview(
    val entryId: String,
    val dateLabel: String,
    val excerpt: String,
)

/** 卡片里最多放这么多字（界面 2 行截断，这里只是不把整篇长文塞进 Text）。 */
private const val ExcerptMaxChars = 120

/**
 * [diary] 是仓库给的「按日期倒序」列表：取第一篇正文不为空的。
 * 没有日记（或全是空篇）返回 null，卡片整张不显示。
 */
internal fun drawerDiaryPreview(diary: List<DiaryEntry>, today: LocalDate = LocalDate.now()): DrawerDiaryPreview? {
    for (entry in diary) {
        val excerpt = diaryExcerpt(entry.content)
        if (excerpt.isEmpty()) continue
        return DrawerDiaryPreview(entryId = entry.id, dateLabel = diaryDateLabel(entry.date, today), excerpt = excerpt)
    }
    return null
}

private val MonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val YearMonthDay: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

/** 今年「10月26日」，往年「2025年10月26日」（README §1.4）。 */
internal fun diaryDateLabel(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) date.format(MonthDay) else date.format(YearMonthDay)

private val SegmentTime = Regex("""^\[\d{1,2}:\d{2}(?::\d{2})?]\s*""")
private val LinePrefix = Regex("""^(?:#{1,6}\s+|>\s?|[-*+]\s+\[[ xX]]\s+|[-*+]\s+|\d+[.)]\s+)+""")
private val Rule = Regex("""^(?:[-*_]\s*){3,}$""")
private val Image = Regex("""!\[([^\]]*)]\([^)]*\)""")
private val Link = Regex("""\[([^\]]+)]\([^)]*\)""")
private val Emphasis = Regex("""(\*\*|__|~~|\*|_|`+)""")
private val Spaces = Regex("""\s+""")

/**
 * 日记正文 → 一段纯文字：逐行去掉段首时间「[21:30]」、标题 / 引用 / 列表 / 分隔线标记，
 * 图片只留替代文字、链接只留文字，再去掉强调 / 删除线 / 行内代码符号；空行丢掉，行与行用一个空格接起来。
 */
internal fun diaryExcerpt(content: String): String {
    val text = content.lineSequence()
        .map { it.trim() }
        .filterNot { it.isEmpty() || it.startsWith("```") || Rule.matches(it) }
        .map { line ->
            var l = line.replace(SegmentTime, "")
            l = l.replace(LinePrefix, "")
            l = l.replace(Image, "$1").replace(Link, "$1")
            l.replace(Emphasis, "").trim()
        }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Spaces, " ")
        .trim()
    return if (text.length > ExcerptMaxChars) text.take(ExcerptMaxChars).trimEnd() + "…" else text
}
