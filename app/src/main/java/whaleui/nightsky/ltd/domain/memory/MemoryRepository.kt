package whaleui.nightsky.ltd.domain.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

// ---------------------------------------------------------------------------
// 仓库接口
//
// 实现是 Room 版（data/RoomMemoryRepository、RoomDiaryRepository），测试里用内存版 Fake。
// 设计约束：仓库只暴露 Flow 订阅 + 写方法，不暴露任何 SQL / DAO 类型。
// ---------------------------------------------------------------------------

interface MemoryRepository {

    /** 全部记忆，顺序就是页面的展示顺序（新增的排在最前）。 */
    fun observeAll(): Flow<List<Memory>>

    /** 新增。id / createdAt / updatedAt 由仓库生成；默认来源是「你添加的」。 */
    suspend fun add(
        content: String,
        category: MemoryCategory,
        origin: MemoryOrigin = MemoryOrigin.UserAdded,
    ): Memory

    /** 更新内容 + 分类，并刷新 updatedAt；id 不存在时返回 null。 */
    suspend fun update(id: String, content: String, category: MemoryCategory): Memory?

    /** 删除一条。撤销由 [restore] 负责。 */
    suspend fun delete(id: String)

    /**
     * 撤销删除：按 [index] 把这条记忆插回原来的位置。
     * [index] 是删除时它在 [observeAll] 列表里的下标。
     */
    suspend fun restore(memory: Memory, index: Int)

    /** 清空全部记忆（设置页「清空数据」用）。 */
    suspend fun clearAll()

    /** 导出全部记忆为 Markdown 文本，供设置页以后的「导出记忆」使用。 */
    suspend fun exportAll(): String
}

interface DiaryRepository {

    /** 全部日记，仓库保证按日期倒序。 */
    fun observeAll(): Flow<List<DiaryEntry>>

    /** 日记由她写，界面不提供新增和编辑，只有删除。 */
    suspend fun delete(id: String)

    /** 撤销删除。日记按日期排序，恢复时交给仓库重新排回原位。 */
    suspend fun restore(entry: DiaryEntry)

    /** 清空全部日记（设置页「清空数据」用）。 */
    suspend fun clearAll()
}
