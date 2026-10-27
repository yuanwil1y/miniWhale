package whaleui.nightsky.ltd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `schedule_task` 表的读写入口。排序语义见 [ScheduleTaskEntity.sortIndex]。 */
@Dao
interface WhaleScheduleDao {

    /** 界面顺序：sort_index 大的在前，也就是新增的排最前。 */
    @Query("SELECT * FROM schedule_task ORDER BY sort_index DESC")
    fun observeAll(): Flow<List<ScheduleTaskEntity>>

    /** 与 [observeAll] 同一顺序的一次性读取，撤销删除时用来算插入位置。 */
    @Query("SELECT * FROM schedule_task ORDER BY sort_index DESC")
    suspend fun listAll(): List<ScheduleTaskEntity>

    @Query("SELECT * FROM schedule_task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScheduleTaskEntity?

    /** 空表返回 0；新条目用 max + 1 保证排在最前。 */
    @Query("SELECT COALESCE(MAX(sort_index), 0) FROM schedule_task")
    suspend fun maxSortIndex(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduleTaskEntity)

    /** 撤销删除后整表重排 sort_index 用，一次写入。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(tasks: List<ScheduleTaskEntity>)

    @Query("UPDATE schedule_task SET enabled = :enabled WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean): Int

    /** 调度器回写：随机任务今天挑好的时间。 */
    @Query("UPDATE schedule_task SET today_picked_date = :date, today_picked_time = :time WHERE id = :id")
    suspend fun updateTodayPicked(id: String, date: String, time: String): Int

    /** 调度器回写：上次发送时间（epoch 毫秒）。 */
    @Query("UPDATE schedule_task SET last_sent_at = :sentAt WHERE id = :id")
    suspend fun updateLastSentAt(id: String, sentAt: Long): Int

    /** 调度器判断「今天有没有挑过」用。 */
    @Query("SELECT today_picked_date FROM schedule_task WHERE id = :id LIMIT 1")
    suspend fun todayPickedDate(id: String): String?

    @Query("DELETE FROM schedule_task WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
