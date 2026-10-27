package whaleui.nightsky.ltd.ui.profile

import androidx.compose.runtime.Immutable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Token 热力图的纯计算（B，README §5 B）：按天汇总 → 截出最近 N 周 → 算档位 → 文案。
// 不碰 Android / 数据库，方便单测；读消息在 ProfileViewModel 里，后台线程跑一次。
// ---------------------------------------------------------------------------

/** 一格。[level] 0 = 没用 token（空格底色），1–4 = 主色的四档深浅。 */
@Immutable
data class HeatCell(val date: LocalDate, val tokens: Long, val level: Int)

/**
 * 热力图网格：[columns] 列（周，最左最早）× 7 行（周一在上）。
 * 每列固定 7 格；最后一列里「今天之后」的日子为 null（不画）。
 */
@Immutable
data class HeatmapGrid(
    val weeks: Int,
    val columns: List<List<HeatCell?>>,
    /** 窗口内（第一列周一到今天）的 token 总数 */
    val total: Long,
    /** 窗口内非零天的 P75；没有非零天时为 0 */
    val p75: Long,
    /** 每列上方的月份标签（「6月」），不写的列为 null */
    val monthLabels: List<String?>,
)

object TokenHeatmapMath {

    /** 档位数（不含 0） */
    const val LEVELS = 4

    /**
     * 按本地日期累加 token。[entries] 是每条消息的（本地日期，usage.totalTokens），
     * usage 为空的消息传 null，记 0；负数（不该出现）也按 0。
     */
    fun dailyTotals(entries: Sequence<Pair<LocalDate, Int?>>): Map<LocalDate, Long> {
        val totals = HashMap<LocalDate, Long>()
        for ((date, tokens) in entries) {
            val n = tokens?.coerceAtLeast(0) ?: 0
            if (n == 0) continue
            totals[date] = (totals[date] ?: 0L) + n
        }
        return totals
    }

    /**
     * 最近 [weeks] 周的网格：最后一列是今天所在的周（周一开头），第一列往前推 weeks − 1 周。
     * P75 只在窗口内的非零天上取（最近秩法：排序后第 ceil(0.75·n) 个）；
     * n ≤ 3 时它就是最大值，所以只聊过一两天时这一两天直接满档，不会因为样本少而算不出来。
     */
    fun grid(daily: Map<LocalDate, Long>, today: LocalDate, weeks: Int): HeatmapGrid {
        val safeWeeks = weeks.coerceAtLeast(1)
        val lastMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = lastMonday.minusWeeks((safeWeeks - 1).toLong())

        val inWindow = daily.filter { (date, n) -> n > 0 && !date.isBefore(start) && !date.isAfter(today) }
        val p75 = percentile75(inWindow.values)
        val columns = (0 until safeWeeks).map { week ->
            val monday = start.plusWeeks(week.toLong())
            (0 until 7).map { day ->
                val date = monday.plusDays(day.toLong())
                if (date.isAfter(today)) {
                    null
                } else {
                    val tokens = inWindow[date] ?: 0L
                    HeatCell(date, tokens, level(tokens, p75))
                }
            }
        }
        return HeatmapGrid(
            weeks = safeWeeks,
            columns = columns,
            total = inWindow.values.sum(),
            p75 = p75,
            monthLabels = monthLabels(start, safeWeeks),
        )
    }

    /** 最近秩 P75；空集合为 0。 */
    fun percentile75(values: Collection<Long>): Long {
        val positive = values.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return 0L
        val rank = ceil(positive.size * 0.75).toInt().coerceIn(1, positive.size)
        return positive[rank - 1]
    }

    /**
     * 档位：0 token → 0；否则 ceil(4 × sqrt(当天 / P75)) 夹到 1–4（达到 P75 就满档）。
     * 减一个极小量再取整：正好落在档位边界（如 4 × sqrt(0.5625) = 3）时不因浮点误差跳到下一档。
     */
    fun level(tokens: Long, p75: Long): Int {
        if (tokens <= 0L) return 0
        if (p75 <= 0L) return LEVELS
        val raw = LEVELS * sqrt(tokens.toDouble() / p75.toDouble())
        return ceil(raw - 1e-9).toInt().coerceIn(1, LEVELS)
    }

    /**
     * 每列的月份标签：这一列的周一和上一列不在同一个月时写新月份。
     * 第一列总写；但如果第 2、3 列就换月（标签会挤在一起），第一列就不写。
     */
    fun monthLabels(start: LocalDate, weeks: Int): List<String?> {
        val mondays = (0 until weeks).map { start.plusWeeks(it.toLong()) }
        val labels = mondays.mapIndexed { i, monday ->
            if (i == 0 || monday.month != mondays[i - 1].month) "${monday.monthValue}月" else null
        }.toMutableList()
        if (weeks > 1 && labels.drop(1).take(2).any { it != null }) labels[0] = null
        return labels
    }

    /**
     * 大数写法（README §1.3）：< 10000 原样；≥ 1 万写「1.2 万」（一位小数，四舍五入，「.0」省掉）；
     * ≥ 1 亿写「1.2 亿」（自定：20 周里重度使用可能过亿）。
     */
    fun formatCount(n: Long): String = when {
        n < 10_000L -> n.toString()
        n < 100_000_000L -> oneDecimal(n, 10_000L) + " 万"
        else -> oneDecimal(n, 100_000_000L) + " 亿"
    }

    private fun oneDecimal(n: Long, unit: Long): String {
        val tenths = (n.toDouble() * 10 / unit).roundToLong()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0L) whole.toString() else "$whole.$frac"
    }

    /** 日期写法（README §1.4）：今年「10月27日」，往年「2025年10月26日」。 */
    fun formatDate(date: LocalDate, today: LocalDate): String =
        if (date.year == today.year) {
            "${date.monthValue}月${date.dayOfMonth}日"
        } else {
            "${date.year}年${date.monthValue}月${date.dayOfMonth}日"
        }

    /** 点格子的小提示：「10月27日 · 1.2 万 token」 */
    fun cellHint(cell: HeatCell, today: LocalDate): String =
        "${formatDate(cell.date, today)} · ${formatCount(cell.tokens)} token"

    /** 网格下方一行：「近 20 周共 N token」 */
    fun totalLine(grid: HeatmapGrid): String = "近 ${grid.weeks} 周共 ${formatCount(grid.total)} token"

    /** 按可用宽度定周数：格子 [cell]、间距 [gap]，放得下几列就几列，夹在 [minWeeks]..[maxWeeks]。 */
    fun weeksFor(availableWidth: Float, cell: Float, gap: Float, minWeeks: Int = 12, maxWeeks: Int = 30): Int {
        if (availableWidth <= 0f || cell <= 0f) return minWeeks
        val n = ((availableWidth + gap) / (cell + gap)).toInt()
        return n.coerceIn(minWeeks, maxWeeks)
    }
}
