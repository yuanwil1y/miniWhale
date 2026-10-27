package me.rerere.rikkahub.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import kotlin.uuid.Uuid

private const val TAG = "GenerationKeepAlive"

/**
 * 一轮生成期间让进程保持可运行（W-020）。
 *
 * [hold] 在这一轮开始时调用，返回的函数在这一轮结束（含失败、取消）时调用一次。
 */
fun interface GenerationKeepAlive {
    fun hold(conversationId: Uuid): () -> Unit
}

/**
 * 先拉生成用的前台服务；拉不起来（Android 12+ 在后台、又不在豁免窗口内，
 * 例如他发完消息 7 秒内按了 Home，批次到点时 App 已经在后台）就排一个 expedited WorkManager 任务，
 * 让它一直等到这一轮结束：Worker 运行期间进程由 JobScheduler 保活，不会进 cached 被冻结。
 *
 * 生成本身仍然跑在 ChatService 的 AppScope 里，Worker 只负责「占着」，不重跑这一轮，
 * 所以不会出现同一批消息被回两次。和闹钟路径（[whaleui.nightsky.ltd.alarm.WhaleAlarmKeepAlive]）同一个思路：
 * 能拉前台服务就拉，不能就交给 expedited 任务。
 *
 * 三个动作都可以注入，JVM 单测用假实现（`GenerationKeepAliveTest`）。
 */
class ForegroundOrWorkerKeepAlive(
    /** 拉起前台服务；被系统拒绝时返回 false */
    private val startForeground: (generationId: Uuid, conversationId: Uuid) -> Boolean,
    private val stopForeground: (generationId: Uuid) -> Unit,
    /** 排一个等 [GenerationHolds.await] 的 expedited 任务 */
    private val enqueueWorker: (generationId: Uuid) -> Unit,
    private val holds: GenerationHolds = GenerationHolds.process,
    private val log: (String) -> Unit = { runCatching { Log.i(TAG, it) } },
) : GenerationKeepAlive {

    override fun hold(conversationId: Uuid): () -> Unit {
        val generationId = Uuid.random()
        if (startForeground(generationId, conversationId)) {
            log("generation $generationId held by foreground service")
            return { stopForeground(generationId) }
        }
        // 先登记再排任务：Worker 跑起来时一定找得到这一轮
        holds.open(generationId)
        val queued = try {
            enqueueWorker(generationId)
            true
        } catch (e: Exception) {
            false
        }
        log("foreground service not allowed, generation $generationId kept alive by expedited worker (queued=$queued)")
        return { holds.close(generationId) }
    }

    companion object {
        fun forContext(context: Context): ForegroundOrWorkerKeepAlive {
            val app = context.applicationContext
            return ForegroundOrWorkerKeepAlive(
                startForeground = { generationId, conversationId ->
                    ChatGenerationForegroundService.acquire(app, generationId, conversationId)
                },
                stopForeground = { generationId -> ChatGenerationForegroundService.release(app, generationId) },
                enqueueWorker = { generationId -> ChatGenerationWorker.enqueue(app, generationId) },
            )
        }
    }
}

/** 进程内「这一轮还没结束」的登记表；Worker 按 id 等它结束。线上只用 [process] 这一份 */
class GenerationHolds {
    companion object {
        val process = GenerationHolds()
    }

    private val holds = ConcurrentHashMap<Uuid, CompletableDeferred<Unit>>()

    fun open(generationId: Uuid) {
        holds[generationId] = CompletableDeferred()
    }

    fun close(generationId: Uuid) {
        holds.remove(generationId)?.complete(Unit)
    }

    fun isOpen(generationId: Uuid): Boolean = holds.containsKey(generationId)

    /** 等这一轮结束；没有登记（已经结束，或进程重启过）时立刻返回 */
    suspend fun await(generationId: Uuid) {
        holds[generationId]?.await()
    }
}
