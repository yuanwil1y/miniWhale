package whaleui.nightsky.ltd.whale

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

/**
 * 他连着发几条时「等他发完再合成一次请求」的窗口（任务书 3.7）。
 *
 * 停手约 7 秒后自动触发；每发出一条（文字/图片/表情）就重新计时。常量固定为 7000，
 * 测试里通过 [WhaleBatchScheduler] 注入虚拟时钟，不需要真的等。
 */
const val WhaleBatchWindowMs: Long = 7_000L

/** 计时器抽象：生产用协程 `delay`，单测用虚拟时间。 */
interface WhaleBatchScheduler {
    /** 延迟 [delayMs] 毫秒后执行 [action]；返回取消函数。 */
    fun schedule(delayMs: Long, action: () -> Unit): () -> Unit
}

/** 生产实现：挂在 AppScope 上的协程计时器。 */
class CoroutineWhaleBatchScheduler(private val scope: CoroutineScope) : WhaleBatchScheduler {
    override fun schedule(delayMs: Long, action: () -> Unit): () -> Unit {
        val job: Job = scope.launch {
            delay(delayMs)
            action()
        }
        return { job.cancel() }
    }
}

/**
 * 待发批次（任务书 3.7 第 3 条）。**纯 Kotlin，不依赖 Android**，所以可以在 JVM 单测里
 * 用虚拟时间完整覆盖「0s/2s/5s 各发一条 → 12s 只发一次请求」这类场景。
 *
 * 职责只有一件：记住「这一批还有哪些他已经发出、但还没触发她回复的消息」，
 * 以及什么时候该触发。消息本身早就写进数据库了（界面照原样显示独立气泡），
 * 合并发给模型由 `WhaleCompatTransformer.mergeConsecutiveUserMessages` 负责。
 *
 * 触发规则：
 * - 每次 [record] 重新计时 7 秒；计时到了她没在回 → 触发；
 * - 计时到了她正在回 → 记住「待触发」，等她这一轮完全结束后立刻触发（不再等 7 秒）；
 * - 批次里的消息被删掉 → 从批次移除；空了就取消计时；
 * - [flushNow] 给闹钟轮用：不等计时，立刻把批次发出去。
 */
class WhalePendingBatch(
    private val scheduler: WhaleBatchScheduler,
    /** 这个会话现在是不是正在生成（她正在回）。 */
    private val isGenerating: (Uuid) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = WhaleBatchWindowMs,
) {

    private class State {
        /** 已经发出、还在等触发的消息（按发出顺序）。 */
        val messages: LinkedHashMap<Uuid, Long> = LinkedHashMap()
        var cancelTimer: (() -> Unit)? = null
        /** 计时到了但她还在回（或触发时没能启动）：等她这一轮结束立刻触发。 */
        var pendingTrigger: Boolean = false
        /** 触发时要调的回调（每次 record 时更新）；返回这一轮是否真的启动了。 */
        var trigger: (() -> Boolean)? = null
    }

    private val states = ConcurrentHashMap<Uuid, State>()

    private fun stateOf(conversationId: Uuid): State =
        states.computeIfAbsent(conversationId) { State() }

    /**
     * 他发出一条：入批、重新计时。
     *
     * [trigger] 替这一批跑一轮回复，返回**是否真的启动了**：返回 false（她正在回）时
     * 批次原样保留并记成待触发，她这一轮结束后再触发（W-011）。
     */
    fun record(conversationId: Uuid, messageId: Uuid, trigger: () -> Boolean) {
        val state = stateOf(conversationId)
        synchronized(state) {
            state.messages[messageId] = clock()
            state.trigger = trigger
            state.cancelTimer?.invoke()
            state.cancelTimer = scheduler.schedule(windowMs) {
                onWindowElapsed(conversationId)
            }
        }
    }

    /** 删掉批次里的某条；批次空了就取消计时。返回是否真的删掉了。 */
    fun remove(conversationId: Uuid, messageId: Uuid): Boolean {
        val state = states[conversationId] ?: return false
        synchronized(state) {
            val removed = state.messages.remove(messageId) != null
            if (state.messages.isEmpty()) {
                state.cancelTimer?.invoke()
                state.cancelTimer = null
                state.pendingTrigger = false
            }
            return removed
        }
    }

    fun hasPending(conversationId: Uuid): Boolean =
        states[conversationId]?.let { synchronized(it) { it.messages.isNotEmpty() } } == true

    fun size(conversationId: Uuid): Int =
        states[conversationId]?.let { synchronized(it) { it.messages.size } } ?: 0

    /**
     * 她这一轮生成结束了。如果计时期间她正在回（[State.pendingTrigger]），立刻触发。
     *
     * @return 是否真的启动了一轮
     */
    fun onGenerationFinished(conversationId: Uuid): Boolean {
        val state = states[conversationId] ?: return false
        return fire(state, onlyIfPending = true)
    }

    /**
     * 不等计时，立刻把批次发出去（闹钟轮遇到待发批次时用）。
     *
     * @return 是否真的启动了一轮；没启动时批次保留并记成待触发
     */
    fun flushNow(conversationId: Uuid): Boolean {
        val state = states[conversationId] ?: return false
        return fire(state, onlyIfPending = false)
    }

    /** 生成开始时调用：批次已经空了的话，顺手清掉「待触发」标记和计时。 */
    fun onGenerationStarted(conversationId: Uuid) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            if (state.messages.isEmpty()) {
                state.pendingTrigger = false
                state.cancelTimer?.invoke()
                state.cancelTimer = null
            }
        }
    }

    /**
     * 标记「她这一轮结束后立刻触发」。
     *
     * 用在两种竞态里：计时到的时候她刚好开始回；或者他发消息时她刚好在回（`ChatService` 会调）。
     */
    fun markPending(conversationId: Uuid) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            if (state.messages.isNotEmpty()) state.pendingTrigger = true
        }
    }

    fun clear(conversationId: Uuid) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            state.messages.clear()
            state.cancelTimer?.invoke()
            state.cancelTimer = null
            state.pendingTrigger = false
        }
    }

    /** 计时到：她没在回就触发；正在回就记住待触发。 */
    private fun onWindowElapsed(conversationId: Uuid) {
        val state = states[conversationId] ?: return
        synchronized(state) {
            if (state.messages.isEmpty()) return
            if (isGenerating(conversationId)) {
                // 不打断她：等她这一轮结束立刻发（任务书 3.7 已确认的决定）
                state.pendingTrigger = true
                return
            }
        }
        fire(state, onlyIfPending = false)
    }

    /**
     * 触发一次（回调在锁外执行）。**只有回调说「启动了」才把这一批移出批次**（W-011）：
     * 没启动时消息留在批次里并记成待触发，等她这一轮结束（[onGenerationFinished]）再来。
     * 触发期间他又发的消息不在这次快照里，留给它们自己的计时。
     */
    private fun fire(state: State, onlyIfPending: Boolean): Boolean {
        val (trigger, snapshot) = synchronized(state) {
            if (state.messages.isEmpty()) {
                state.pendingTrigger = false
                return false
            }
            if (onlyIfPending && !state.pendingTrigger) return false
            val trigger = state.trigger ?: return false
            state.pendingTrigger = false
            state.cancelTimer?.invoke()
            state.cancelTimer = null
            trigger to state.messages.keys.toList()
        }
        val started = runCatching { trigger() }.getOrDefault(false)
        synchronized(state) {
            if (started) {
                snapshot.forEach { state.messages.remove(it) }
            } else if (state.messages.isNotEmpty()) {
                state.pendingTrigger = true
            }
        }
        return started
    }
}
