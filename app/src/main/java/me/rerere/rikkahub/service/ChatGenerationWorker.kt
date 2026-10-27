package me.rerere.rikkahub.service

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.rikkahub.CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.R
import kotlin.uuid.Uuid

/**
 * 生成用的前台服务拉不起来时（W-020），由它占住进程直到这一轮结束。
 *
 * 不跑生成，只等 [GenerationHolds] 里这一轮的登记被关掉：生成仍在 ChatService 里进行，
 * 这个 expedited 任务在运行期间让系统把进程当成「有活在干」，不进 cached、不被冻结。
 * 进程已经重启过（登记表是空的）时立刻结束。
 *
 * Android 12+ expedited 任务不显示通知；Android 12 以下 WorkManager 会把它当前台服务跑，用 [getForegroundInfo] 的通知
 * （文字与生成用的前台服务相同）。
 */
class ChatGenerationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val generationId = inputData.getString(KEY_GENERATION_ID)
            ?.let { runCatching { Uuid.parse(it) }.getOrNull() }
            ?: return Result.success()
        Log.i(TAG, "keep-alive worker started for generation $generationId (open=${GenerationHolds.process.isOpen(generationId)})")
        val finished = withTimeoutOrNull(MAX_HOLD_MS) { GenerationHolds.process.await(generationId) } != null
        Log.i(TAG, "keep-alive worker done for generation $generationId (finished=$finished)")
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
        private const val TAG = "ChatGenerationWorker"
        private const val KEY_GENERATION_ID = "generationId"
        private const val WORK_NAME_PREFIX = "chat-generation-"
        private const val FOREGROUND_NOTIFICATION_ID = 2004

        /** 一轮（含重试、工具步）最多占 10 分钟，和 dataSync 前台服务的单次时长同一量级 */
        private const val MAX_HOLD_MS = 10 * 60 * 1000L

        fun enqueue(context: Context, generationId: Uuid) {
            val request = OneTimeWorkRequestBuilder<ChatGenerationWorker>()
                .setInputData(workDataOf(KEY_GENERATION_ID to generationId.toString()))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_PREFIX + generationId, ExistingWorkPolicy.KEEP, request)
        }
    }
}
