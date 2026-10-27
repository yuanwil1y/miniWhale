package whaleui.nightsky.ltd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 「记忆与日记」页里的一条记忆。
 *
 * 列按 `_contracts/memory-diary-repositories.md` 落库：[category] / [origin]
 * 存枚举的 `name`（`MemoryCategory` / `MemoryOrigin`），读出来时再映射回枚举，
 * 这样以后枚举加值不影响已有数据（读不出来的老值回退到默认枚举）。
 *
 * [sortIndex] 越大越靠前：新增取当前最大值 + 1；撤销删除时按 [index] 插回后
 * 整表重排 sort_index。因此查询只要 `ORDER BY sort_index DESC` 就是界面顺序。
 */
@Entity(tableName = "memory_item")
data class MemoryItemEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val category: String,
    val origin: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    @ColumnInfo("updated_at")
    val updatedAt: Long,
    @ColumnInfo("sort_index")
    val sortIndex: Long,
)
