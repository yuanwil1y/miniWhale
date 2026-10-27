package whaleui.nightsky.ltd.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** `her_alarm` 表的读写入口。 */
@Dao
interface WhaleHerAlarmDao {

    /** 全部闹钟，按触发时间正序（时间线从上往下）。 */
    @Query("SELECT * FROM her_alarm ORDER BY fire_at ASC")
    fun observeAll(): Flow<List<HerAlarmEntity>>

    /** 下一个还没触发、且触发时间不早于 [now] 的闹钟。 */
    @Query(
        "SELECT * FROM her_alarm WHERE fired = 0 AND fire_at >= :now " +
            "ORDER BY fire_at ASC LIMIT 1"
    )
    suspend fun nextPending(now: Long): HerAlarmEntity?

    /** `[start, end)` 之间还没触发的闹钟，按触发时间正序；时间线按「今天」取用。 */
    @Query(
        "SELECT * FROM her_alarm WHERE fired = 0 AND fire_at >= :start AND fire_at < :end " +
            "ORDER BY fire_at ASC"
    )
    suspend fun pendingBetween(start: Long, end: Long): List<HerAlarmEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alarm: HerAlarmEntity)

    @Query("UPDATE her_alarm SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: String): Int

    @Query("DELETE FROM her_alarm WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
