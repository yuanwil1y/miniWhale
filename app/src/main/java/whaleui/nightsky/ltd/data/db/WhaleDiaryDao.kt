package whaleui.nightsky.ltd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `diary_entry` 表的读写入口。每天一行，按日期倒序就是界面顺序。 */
@Dao
interface WhaleDiaryDao {

    @Query("SELECT * FROM diary_entry ORDER BY date DESC, id DESC")
    fun observeAll(): Flow<List<DiaryEntryEntity>>

    @Query("SELECT * FROM diary_entry ORDER BY date DESC, id DESC")
    suspend fun listAll(): List<DiaryEntryEntity>

    @Query("SELECT * FROM diary_entry WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): DiaryEntryEntity?

    /** [date] 是 `yyyy-MM-dd`；唯一索引保证一天最多一行。 */
    @Query("SELECT * FROM diary_entry WHERE date = :date LIMIT 1")
    suspend fun getByDate(date: String): DiaryEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: DiaryEntryEntity)

    @Query(
        "UPDATE diary_entry SET content = :content, weather = :weather, updated_at = :updatedAt " +
            "WHERE id = :id"
    )
    suspend fun updateContent(
        id: String,
        content: String,
        weather: String,
        updatedAt: Long,
    ): Int

    @Query("DELETE FROM diary_entry WHERE id = :id")
    suspend fun deleteById(id: String): Int

    /** 清空整张表（设置页「清空数据」用）。 */
    @Query("DELETE FROM diary_entry")
    suspend fun deleteAll()
}
