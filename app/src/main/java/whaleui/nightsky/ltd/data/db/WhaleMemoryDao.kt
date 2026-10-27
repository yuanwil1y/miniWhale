package whaleui.nightsky.ltd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `memory_item` 表的读写入口。排序语义见 [MemoryItemEntity.sortIndex]。 */
@Dao
interface WhaleMemoryDao {

    /** 界面顺序：sort_index 大的在前，也就是新增的排最前。 */
    @Query("SELECT * FROM memory_item ORDER BY sort_index DESC")
    fun observeAll(): Flow<List<MemoryItemEntity>>

    /** 与 [observeAll] 同一顺序的一次性读取，撤销删除时用来算插入位置。 */
    @Query("SELECT * FROM memory_item ORDER BY sort_index DESC")
    suspend fun listAll(): List<MemoryItemEntity>

    @Query("SELECT * FROM memory_item WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): MemoryItemEntity?

    /** 空表返回 0；新条目用 max + 1 保证排在最前。 */
    @Query("SELECT COALESCE(MAX(sort_index), 0) FROM memory_item")
    suspend fun maxSortIndex(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MemoryItemEntity)

    /** 撤销删除后整表重排 sort_index 用，一次写入。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MemoryItemEntity>)

    @Query(
        "UPDATE memory_item SET content = :content, category = :category, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateContent(
        id: String,
        content: String,
        category: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM memory_item WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** 清空整张表（设置页「清空数据」用）。 */
    @Query("DELETE FROM memory_item")
    suspend fun deleteAll()
}
