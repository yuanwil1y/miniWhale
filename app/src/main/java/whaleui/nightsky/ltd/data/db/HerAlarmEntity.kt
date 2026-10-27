package whaleui.nightsky.ltd.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 她自己定的一次性闹钟（任务书 §10 6.5：`set_alarm(time, note)`）。
 *
 * 这张表只给 `set_alarm` 工具和定时任务页的时间线用，和 [ScheduleTaskEntity] 分开：
 * 她的闹钟是「某个时刻说某件事」，不是每天重复的任务。
 *
 * [fired]：0/1，触发过就置 1；时间线只显示 `fired = 0` 且 `fire_at` 在今天的那些。
 */
@Entity(tableName = "her_alarm")
data class HerAlarmEntity(
    @PrimaryKey
    val id: String,
    /** 触发时刻，epoch 毫秒。 */
    @ColumnInfo("fire_at")
    val fireAt: Long,
    /** 写给到时候的她自己看的备忘。 */
    val note: String,
    @ColumnInfo("created_at")
    val createdAt: Long,
    val fired: Boolean,
)
