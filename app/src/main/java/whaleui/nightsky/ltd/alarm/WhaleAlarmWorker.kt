package whaleui.nightsky.ltd.alarm

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 闹钟链路的 WorkManager 入口，两种用途：
 *
 * 1. **等网**（[enqueue]）：[WhaleAlarmScheduler.handleFire] 发现当前没有网络时，排一个带
 *    `NetworkType.CONNECTED` 约束的任务，按 taskId 去重（REPLACE）；有网之后交给
 *    [WhaleAlarmScheduler.runAlarmTurnWhenOnline]（不再查网络，迟到 / 免打扰 / 1–8 点判定照旧）。
 * 2. **接手到点的闹钟**（[enqueueTaskFire] / [enqueueHerFire]）：Receiver 拉不起前台服务时（W-007 / W-020），
 *    排一个 expedited 任务跑同一套 `handleFire`。Worker 运行期间进程由系统保活；Android 12 以下
 *    expedited 任务以前台服务运行，用 [getForegroundInfo] 的通知。
 */
class WhaleAlarmWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params), KoinComponent {

    override suspend fun doWork(): Result {
        try {
            when (inputData.getString(KEY_MODE)) {
                MODE_TASK_FIRE -> {
                    val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotEmpty() } ?: return Result.success()
                    val scheduledAtMillis = inputData.getLong(KEY_SCHEDULED_AT, 0L)
                    if (scheduledAtMillis <= 0L) return Result.success()
                    get<WhaleAlarmScheduler>().handleFire(
                        taskId,
                        scheduledAtMillis,
                        inputData.getInt(KEY_ATTEMPT, 0),
                        inputData.getLong(KEY_STARTED_AT, 0L),
                    )
                }

                MODE_HER_FIRE -> {
                    val id = inputData.getString(KEY_HER_ID)?.takeIf { it.isNotEmpty() } ?: return Result.success()
                    get<WhaleHerAlarmScheduler>().handleFire(
                        id,
                        inputData.getString(KEY_NOTE).orEmpty(),
                        inputData.getInt(KEY_ATTEMPT, 0),
                        inputData.getLong(KEY_STARTED_AT, 0L),
                    )
                }

                else -> {
                    val scheduler = get<WhaleAlarmScheduler>()
                    val taskId = inputData.getString(KEY_TASK_ID)?.takeIf { it.isNotEmpty() } ?: return Result.success()
                    val scheduledAtMillis = inputData.getLong(KEY_SCHEDULED_AT, 0L)
                    if (scheduledAtMillis <= 0L) return Result.success()
                    scheduler.runAlarmTurnWhenOnline(taskId, scheduledAtMillis)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 调度层内部已兜底；这里不重试，避免网络抖动时无限重排（顺延 / 看门狗由闹钟负责）。
        }
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_whale)
            .setContentTitle(applicationContext.getString(R.string.app_name))
            .setContentText(applicationContext.getString(R.string.notification_live_update_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_TASK_ID: String = "taskId"
        const val KEY_SCHEDULED_AT: String = "scheduledAtMillis"
        private const val KEY_MODE = "mode"
        private const val KEY_ATTEMPT = "attempt"
        private const val KEY_STARTED_AT = "startedAtMillis"
        private const val KEY_HER_ID = "herAlarmId"
        private const val KEY_NOTE = "note"
        private const val MODE_TASK_FIRE = "task-fire"
        private const val MODE_HER_FIRE = "her-fire"

        private const val WORK_NAME_PREFIX = "whale-alarm-"
        private const val FIRE_WORK_PREFIX = "whale-fire-"
        private const val FOREGROUND_NOTIFICATION_ID = 2003

        /** 排一个「有网就跑」的唯一任务（同一个 taskId 只保留最新的一个）。 */
        fun enqueue(context: Context, taskId: String, scheduledAtMillis: Long) {
            val request = OneTimeWorkRequestBuilder<WhaleAlarmWorker>()
                .setInputData(
                    workDataOf(
                        KEY_TASK_ID to taskId,
                        KEY_SCHEDULED_AT to scheduledAtMillis,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(WORK_NAME_PREFIX + taskId)
                .build()
            enqueueUnique(context, WORK_NAME_PREFIX + taskId, ExistingWorkPolicy.REPLACE, request)
        }

        /** Receiver 拉不起前台服务时，用 expedited 任务接手定时任务的这一次触发。 */
        fun enqueueTaskFire(context: Context, taskId: String, scheduledAtMillis: Long, attempt: Int, startedAtMillis: Long) {
            val request = expedited(
                workDataOf(
                    KEY_MODE to MODE_TASK_FIRE,
                    KEY_TASK_ID to taskId,
                    KEY_SCHEDULED_AT to scheduledAtMillis,
                    KEY_ATTEMPT to attempt,
                    KEY_STARTED_AT to startedAtMillis,
                )
            )
            enqueueUnique(context, "${FIRE_WORK_PREFIX}task-$taskId", ExistingWorkPolicy.KEEP, request)
        }

        /** Receiver 拉不起前台服务时，用 expedited 任务接手她的一次性闹钟。 */
        fun enqueueHerFire(context: Context, id: String, note: String, attempt: Int, startedAtMillis: Long) {
            val request = expedited(
                workDataOf(
                    KEY_MODE to MODE_HER_FIRE,
                    KEY_HER_ID to id,
                    KEY_NOTE to note,
                    KEY_ATTEMPT to attempt,
                    KEY_STARTED_AT to startedAtMillis,
                )
            )
            enqueueUnique(context, "${FIRE_WORK_PREFIX}her-$id", ExistingWorkPolicy.KEEP, request)
        }

        private fun expedited(data: androidx.work.Data): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<WhaleAlarmWorker>()
                .setInputData(data)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

        private fun enqueueUnique(context: Context, name: String, policy: ExistingWorkPolicy, request: OneTimeWorkRequest) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(name, policy, request)
            } catch (e: Exception) {
                // WorkManager 没有初始化（例如 Application 未实现 Configuration.Provider）时不崩广播；
                // 下一次开机 / 进 App 调 scheduleAll 时会重排这条任务。
            }
        }
    }
}
