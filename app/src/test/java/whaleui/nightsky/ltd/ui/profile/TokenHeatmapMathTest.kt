package whaleui.nightsky.ltd.ui.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 资料页 token 热力图（B）：按天汇总、P75、档位夹取、网格窗口、数字和日期文案。 */
class TokenHeatmapMathTest {

    // 2026-10-27 是周二
    private val today = LocalDate.of(2026, 10, 27)

    @Test
    fun dailyTotalsBucketsByLocalDateAndTreatsNullAsZero() {
        val d1 = LocalDate.of(2026, 10, 26)
        val d2 = LocalDate.of(2026, 10, 27)
        val totals = TokenHeatmapMath.dailyTotals(
            sequenceOf(d1 to 100, d1 to null, d1 to 50, d2 to null, d2 to 7, d2 to -3),
        )
        assertEquals(mapOf(d1 to 150L, d2 to 7L), totals)
    }

    @Test
    fun dailyTotalsSkipsDaysWithOnlyNullUsage() {
        val totals = TokenHeatmapMath.dailyTotals(sequenceOf(today to null, today to 0))
        assertTrue(totals.isEmpty())
    }

    @Test
    fun dailyTotalsDoesNotOverflowInt() {
        val totals = TokenHeatmapMath.dailyTotals(sequenceOf(today to Int.MAX_VALUE, today to Int.MAX_VALUE))
        assertEquals(2L * Int.MAX_VALUE, totals[today])
    }

    @Test
    fun percentile75UsesNearestRankOverPositiveValues() {
        assertEquals(0L, TokenHeatmapMath.percentile75(emptyList()))
        assertEquals(0L, TokenHeatmapMath.percentile75(listOf(0L, 0L)))
        // n ≤ 3 → 最大值
        assertEquals(5L, TokenHeatmapMath.percentile75(listOf(5L)))
        assertEquals(9L, TokenHeatmapMath.percentile75(listOf(9L, 1L)))
        assertEquals(9L, TokenHeatmapMath.percentile75(listOf(3L, 9L, 1L)))
        // n = 4 → 第 3 个；n = 8 → 第 6 个；0 不算样本
        assertEquals(30L, TokenHeatmapMath.percentile75(listOf(40L, 10L, 30L, 20L, 0L)))
        assertEquals(6L, TokenHeatmapMath.percentile75((1L..8L).toList()))
    }

    @Test
    fun levelIsZeroOnlyForZeroTokens() {
        assertEquals(0, TokenHeatmapMath.level(0L, 1000L))
        // 很少的 token 也至少 1 档
        assertEquals(1, TokenHeatmapMath.level(1L, 1_000_000L))
    }

    @Test
    fun levelFollowsSqrtCurveAndClamps() {
        val p75 = 10_000L
        // 4·sqrt(x) 的边界：1/16 → 1，4/16 → 2，9/16 → 3，1 → 4
        assertEquals(1, TokenHeatmapMath.level(625L, p75))
        assertEquals(2, TokenHeatmapMath.level(626L, p75))
        assertEquals(2, TokenHeatmapMath.level(2_500L, p75))
        assertEquals(3, TokenHeatmapMath.level(5_625L, p75))
        assertEquals(4, TokenHeatmapMath.level(5_626L, p75))
        assertEquals(4, TokenHeatmapMath.level(10_000L, p75))
        // 超过 P75 夹在 4
        assertEquals(4, TokenHeatmapMath.level(1_000_000L, p75))
        // 平常聊过的日子（P75 的一半）在 3 档
        assertEquals(3, TokenHeatmapMath.level(5_000L, p75))
    }

    @Test
    fun levelWithoutP75IsFull() {
        assertEquals(4, TokenHeatmapMath.level(3L, 0L))
    }

    @Test
    fun gridEndsOnTodayAndStartsOnMonday() {
        val grid = TokenHeatmapMath.grid(emptyMap(), today, weeks = 20)
        assertEquals(20, grid.columns.size)
        grid.columns.forEach { assertEquals(7, it.size) }
        val first = grid.columns.first().first()!!
        assertEquals(LocalDate.of(2026, 6, 15), first.date)
        assertEquals(java.time.DayOfWeek.MONDAY, first.date.dayOfWeek)
        val last = grid.columns.last()
        assertEquals(LocalDate.of(2026, 10, 26), last[0]!!.date)
        assertEquals(today, last[1]!!.date)
        // 今天之后不画
        (2 until 7).forEach { assertNull(last[it]) }
    }

    @Test
    fun emptyDataGivesEmptyGridWithoutError() {
        val grid = TokenHeatmapMath.grid(emptyMap(), today, weeks = 20)
        assertEquals(0L, grid.total)
        assertEquals(0L, grid.p75)
        assertTrue(grid.columns.flatten().filterNotNull().all { it.level == 0 && it.tokens == 0L })
        assertEquals("近 20 周共 0 token", TokenHeatmapMath.totalLine(grid))
    }

    @Test
    fun gridIgnoresDaysOutsideWindowForTotalAndP75() {
        val daily = mapOf(
            LocalDate.of(2026, 6, 14) to 1_000_000L, // 窗口前一天
            LocalDate.of(2026, 6, 15) to 100L,
            today to 400L,
            today.plusDays(1) to 9_999_999L, // 明天（时钟漂移）
        )
        val grid = TokenHeatmapMath.grid(daily, today, weeks = 20)
        assertEquals(500L, grid.total)
        assertEquals(400L, grid.p75)
        assertEquals(4, grid.columns.last()[1]!!.level)
        // 100 / 400 → 4·sqrt(0.25) = 2
        assertEquals(2, grid.columns.first()[0]!!.level)
    }

    @Test
    fun singleActiveDayIsFullLevel() {
        val grid = TokenHeatmapMath.grid(mapOf(today to 12L), today, weeks = 20)
        assertEquals(12L, grid.p75)
        assertEquals(4, grid.columns.last()[1]!!.level)
    }

    @Test
    fun monthLabelsMarkColumnsWhereMonthChanges() {
        val labels = TokenHeatmapMath.monthLabels(LocalDate.of(2026, 6, 15), 20)
        assertEquals("6月", labels[0])
        assertEquals("7月", labels[3]) // 7 月 6 日那一周
        assertNull(labels[1])
        assertEquals(listOf("6月", "7月", "8月", "9月", "10月"), labels.filterNotNull())
    }

    @Test
    fun firstMonthLabelDroppedWhenNextChangeIsTooClose() {
        // 6 月 22 日开头：第 3 列（7 月 6 日）就换月 → 第一列不写
        val labels = TokenHeatmapMath.monthLabels(LocalDate.of(2026, 6, 22), 10)
        assertNull(labels[0])
        assertEquals("7月", labels[2])
    }

    @Test
    fun formatCountUsesWanAboveTenThousand() {
        assertEquals("0", TokenHeatmapMath.formatCount(0))
        assertEquals("9412", TokenHeatmapMath.formatCount(9_412))
        assertEquals("9999", TokenHeatmapMath.formatCount(9_999))
        assertEquals("1 万", TokenHeatmapMath.formatCount(10_000))
        assertEquals("1.2 万", TokenHeatmapMath.formatCount(12_345))
        assertEquals("1.3 万", TokenHeatmapMath.formatCount(12_500))
        assertEquals("10 万", TokenHeatmapMath.formatCount(99_999))
        assertEquals("356.8 万", TokenHeatmapMath.formatCount(3_567_890))
        assertEquals("1.2 亿", TokenHeatmapMath.formatCount(123_456_789))
    }

    @Test
    fun cellHintAndDateFormat() {
        val cell = HeatCell(today, 12_000L, 3)
        assertEquals("10月27日 · 1.2 万 token", TokenHeatmapMath.cellHint(cell, today))
        assertEquals("2025年12月30日", TokenHeatmapMath.formatDate(LocalDate.of(2025, 12, 30), today))
        assertEquals("3月5日 · 800 token", TokenHeatmapMath.cellHint(HeatCell(LocalDate.of(2026, 3, 5), 800L, 1), today))
    }

    @Test
    fun weeksForFitsWidth() {
        // 333dp 可用、12 + 3 → 22 列
        assertEquals(22, TokenHeatmapMath.weeksFor(333f, 12f, 3f))
        assertEquals(12, TokenHeatmapMath.weeksFor(0f, 12f, 3f))
        assertEquals(30, TokenHeatmapMath.weeksFor(2000f, 12f, 3f))
    }
}
