package whaleui.nightsky.ltd.ui.memory

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Offset
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import whaleui.nightsky.ltd.domain.memory.DiaryEntry
import whaleui.nightsky.ltd.domain.memory.Memory
import whaleui.nightsky.ltd.domain.memory.MemoryCategory

// ---------------------------------------------------------------------------
// 数据模型
//
// 这一页在 Telegram 里没有直接对应物，模型按项目需求定义。
// 界面只读 [MemoryUiState]，所有写操作走 MemoryViewModel → Repository。
// ---------------------------------------------------------------------------

/** 一页里的两个标签。进入页面默认 [Memory]。 */
enum class MemoryTab {
    Memory,
    Diary,
}

// ---------------------------------------------------------------------------
// 界面状态
// ---------------------------------------------------------------------------

/** 编辑 / 新建记忆的底部弹层内容。[memoryId] 为 null 表示新建。 */
@Immutable
data class MemoryEditor(
    val memoryId: String? = null,
    val content: String = "",
    val category: MemoryCategory = MemoryCategory.AboutYou,
) {
    val isNew: Boolean get() = memoryId == null
}

/** 「⋮」或长按菜单的位置，[touch] 是相对页面根布局的坐标（和聊天页一致）。 */
@Immutable
data class MemoryMenu(
    val memoryId: String,
    val touch: Offset,
)

/** 日记的菜单：只有复制、删除两项。 */
@Immutable
data class DiaryMenu(
    val entryId: String,
    val touch: Offset,
)

/** 底部撤销提示里待恢复的条目。 */
@Immutable
sealed interface MemoryUndo {
    val message: String

    @Immutable
    data class MemoryRemoved(val memory: Memory, val index: Int) : MemoryUndo {
        override val message: String get() = "已删除记忆"
    }

    @Immutable
    data class DiaryRemoved(val entry: DiaryEntry) : MemoryUndo {
        override val message: String get() = "已删除日记"
    }
}

/**
 * 记忆与日记页的完整状态。
 *
 * [allMemories] 是仓库原始顺序；[visibleMemories] 在它上面套当前分类。
 * [diary] 由仓库按日期倒序给出。
 */
@Immutable
data class MemoryUiState(
    val isLoading: Boolean = true,
    val tab: MemoryTab = MemoryTab.Memory,
    /** null = 全部。 */
    val category: MemoryCategory? = null,
    val allMemories: List<Memory> = emptyList(),
    val diary: List<DiaryEntry> = emptyList(),
    val editor: MemoryEditor? = null,
    val categoryTargetId: String? = null,
    val menu: MemoryMenu? = null,
    val diaryMenu: DiaryMenu? = null,
    val undo: MemoryUndo? = null,
    val expandedDiaryIds: Set<String> = emptySet(),
    /** 新增一条记忆后 +1，界面据此把列表滚回顶部（否则新条目会插在视口上方）。 */
    val scrollToTopTick: Int = 0,
) {
    val visibleMemories: List<Memory>
        get() {
            return allMemories.filter { memory -> category == null || memory.category == category }
        }

    fun memory(id: String?): Memory? =
        if (id == null) null else allMemories.firstOrNull { it.id == id }

    val categoryTarget: Memory? get() = memory(categoryTargetId)
    val menuTarget: Memory? get() = memory(menu?.memoryId)
    val diaryMenuTarget: DiaryEntry?
        get() = diaryMenu?.let { menu -> diary.firstOrNull { it.id == menu.entryId } }

    /** 日记按「年-月」分组；只有跨两个月以上时才展示分组标题。 */
    fun diaryMonths(): List<Pair<YearMonth, List<DiaryEntry>>> {
        val grouped = diary.groupBy { YearMonth.from(it.date) }
        return grouped.entries
            .sortedByDescending { it.key }
            .map { it.key to it.value }
    }

    fun isDiaryExpanded(id: String): Boolean = id in expandedDiaryIds
}

// ---------------------------------------------------------------------------
// 日期格式化
// ---------------------------------------------------------------------------

private val MonthDayFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val YearMonthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月", Locale.CHINA)
private val MonthFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月", Locale.CHINA)

/** 毫秒时间戳 → 本地日期。 */
fun Long.toLocalDate(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

/** 本地日期 → 当天 0 点的毫秒时间戳（假数据用）。 */
fun LocalDate.toEpochMillis(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 今天 / 昨天 / 几月几日。 */
fun memoryDateLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "今天"
    today.minusDays(1) -> "昨天"
    else -> date.format(MonthDayFormatter)
}

/** 日记卡片上的日期：几月几日。 */
fun monthDayLabel(date: LocalDate): String = date.format(MonthDayFormatter)

/** 月份分组标题：今年写「10月」，跨年写「2025年10月」。 */
fun monthLabel(month: YearMonth, today: LocalDate = LocalDate.now()): String =
    if (month.year == today.year) month.format(MonthFormatter) else month.format(YearMonthFormatter)

private val WeekdayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 周几：周一到周日。 */
fun weekdayLabel(date: LocalDate): String = WeekdayLabels[date.dayOfWeek.value - 1]
