package whaleui.nightsky.ltd.alarm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.service.ChatGenerationForegroundService
import kotlinx.coroutines.CoroutineScope
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.whale.WhaleRuntimeStore
import kotlin.uuid.Uuid

/**
 * 闹钟 Receiver 收到「到点了」以后怎么把活交出去（W-007）。
 *
 * Receiver 不再 `goAsync()` 等整轮生成（带工具的一轮很容易超过广播时限，超时后进程被静默杀掉）：
 *
 * 1. 在 `onReceive` 里（主线程、闹钟刚送达）**同步**拉起生成用的前台服务占一个位置。
 *    精确闹钟（`setExactAndAllowWhileIdle`）送达时系统给约 10 秒的前台服务启动豁免，
 *    所以这在 Android 12+ 后台也是合法的；之后这一轮自己的 `launchGenerationJob` 再拉前台服务时，
 *    App 已经有前台服务在跑，同样合法（W-020）。
 * 2. 活交给应用级 [AppScope]，Receiver 立刻返回；跑完（包括排下一次 / markFired）再释放前台服务。
 * 3. 前台服务拉不起来（非精确兜底闹钟没有豁免）时交给 expedited WorkManager
 *    （[fallback]）：Worker 跑的时候进程由 JobScheduler 保活，Android 12 以下由 Worker 自己的前台通知保活。
 */
internal object WhaleAlarmKeepAlive {

    private const val TAG = "WhaleAlarmKeepAlive"

    /** [scope] 是应用级的 [AppScope]（Receiver 从 Koin 取来传进来）。 */
    fun launch(
        context: Context,
        scope: CoroutineScope,
        label: String,
        fallback: () -> Unit,
        block: suspend () -> Unit,
    ) {
        val app = context.applicationContext
        val conversationId = WhaleGate.conversationId
            ?: runCatching { WhaleRuntimeStore(app).fixedConversationId }.getOrNull()
            ?: Uuid.random()
        val holdId = Uuid.random()
        if (!ChatGenerationForegroundService.acquire(app, holdId, conversationId)) {
            Log.w(TAG, "$label: foreground service not allowed, hand over to WorkManager")
            fallback()
            return
        }
        Log.i(TAG, "$label: foreground hold acquired, receiver returns")
        scope.launch(Dispatchers.IO) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "$label failed", e)
            } finally {
                ChatGenerationForegroundService.release(app, holdId)
                Log.i(TAG, "$label: done, foreground hold released")
            }
        }
    }
}
