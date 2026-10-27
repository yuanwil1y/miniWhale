package whaleui.nightsky.ltd.domain.memory

import androidx.compose.runtime.Immutable
import java.time.LocalDate

// 记忆与日记的领域模型（R1 / W-050 从 ui.memory 搬来）

/** 记忆分类。三个分类都允许出现在首页筛选条里，[label] 就是界面文案。 */
enum class MemoryCategory(val label: String) {
    AboutYou("关于你"),
    Agreement("约定"),
    Hers("她的"),
}

/** 记忆来源。[HerRecorded] 在卡片上有一个很小的标记，不抢正文。 */
enum class MemoryOrigin(val label: String) {
    UserAdded("你添加的"),
    HerRecorded("她记下的"),
}

/**
 * 一条记忆。
 *
 * [createdAt] / [updatedAt] 用毫秒时间戳：真实存储（Room）里可以直接落 Long，
 * 显示层的「今天 / 昨天 / 几月几日」由 [memoryDateLabel] 从日期推出来。
 */
@Immutable
data class Memory(
    val id: String,
    val content: String,
    val category: MemoryCategory,
    val createdAt: Long,
    val updatedAt: Long,
    val origin: MemoryOrigin,
)

/** 一篇日记。日记由她写，界面只读，所以没有 updatedAt。 */
@Immutable
data class DiaryEntry(
    val id: String,
    val date: LocalDate,
    val weather: String,
    val content: String,
)
