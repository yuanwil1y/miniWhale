package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Conversation
import whaleui.nightsky.ltd.data.hasVisibleContent
import whaleui.nightsky.ltd.whale.WhaleBubbleSlicer
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

/** 会话的持久化，由 ChatService 实现 */
interface ConversationStore {
    /**
     * 落库前对会话的补全（比如把助手配置固定到会话上），结果同样写回内存。
     * 返回的变换在最新状态上原子执行，不能挂起。
     */
    suspend fun prepareWrite(): (Conversation) -> Conversation

    /** [previous] 是上次落库或加载时的状态，从未落库的新会话为 null */
    suspend fun write(previous: Conversation?, current: Conversation)
}

/**
 * 一个会话在内存中的状态，加载之后它就是唯一的权威：所有修改都在最新状态上做变换，
 * 落库时写入内存此刻的内容，不接受外部传入的整份快照，数据库里的旧版本也不会再读回来覆盖内存。
 */
class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val onIdle: (Uuid) -> Unit,
    private val onGenerationFinished: (Uuid, Throwable?) -> Unit = { _, _ -> },
    /** 没有引用、也不在生成时仍要留着这个会话（他还有待发批次，W-022）；为 true 时不回收 */
    private val isRetained: () -> Boolean = { false },
) {
    // 会话状态
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<Conversation> = _state.asStateFlow()
    private val initialized = MutableStateFlow(false)

    /**
     * 只发加载完成之后的内容（W-022）：加载前 [state] 里是占位的空会话，发给界面会被当成「消息全没了」。
     * 先等加载完成再收 [state]；那时 [state] 已经是加载结果，所以收到的第一条就是真实内容。
     */
    val loadedState: Flow<Conversation> = flow {
        initialized.first { it }
        emitAll(state)
    }
    private val initializationMutex = Mutex()
    private val writeMutex = Mutex()

    @Volatile
    var isInitialized = false
        private set

    // 上次落库、加载或新建时的状态，内存还是这个实例就说明没有要落库的修改；只在 writeMutex 内修改
    private var lastWritten: Conversation = initial

    @Volatile
    var existsInDatabase = false
        private set

    /**
     * 页面切换和 SSE 重连只加载一次。[load] 返回 null 表示数据库里没有这个会话，改用 [create] 新建。
     * 加载完成前会话不接受修改，所以不会出现加载结果覆盖新内容的情况。
     */
    suspend fun initialize(
        load: suspend () -> Conversation?,
        create: suspend () -> Conversation = { throw IllegalStateException("Conversation $id not found") },
    ) {
        initializationMutex.withLock {
            if (isInitialized) return
            val loaded = load()
            val conversation = loaded ?: create()
            require(conversation.id == id)
            writeMutex.withLock {
                lastWritten = conversation
                existsInDatabase = loaded != null
            }
            synchronized(this) {
                _state.value = conversation
                isInitialized = true
            }
            initialized.value = true
        }
    }

    /** 在最新状态上修改会话，只改内存；需要落库时之后调用 [flush] */
    fun update(transform: (Conversation) -> Conversation): Conversation = checkNotNull(tryUpdate(transform))

    /** 同 [update]，[transform] 返回 null 表示没什么可改的，这时不修改并返回 null */
    @Synchronized
    fun tryUpdate(transform: (Conversation) -> Conversation?): Conversation? {
        check(isInitialized) { "Conversation $id is not loaded yet" }
        val updated = transform(_state.value) ?: return null
        require(updated.id == id)
        _state.value = updated
        return updated
    }

    /**
     * 把内存此刻的状态落库。落库串行执行，每次都读取最新状态，后执行的一定带着更新的内容；
     * 和上次落库相比没有修改，或者是还没内容的新会话时不写。
     */
    suspend fun flush(): Conversation {
        if (!isInitialized) return state.value
        return writeMutex.withLock {
            val latest = state.value
            if (latest === lastWritten) return@withLock latest
            // 还没内容的新会话不落库，也不能提前固定配置：会话开始前聊天页的修改要写回助手
            if (!existsInDatabase && latest.title.isBlank() && latest.messageNodes.isEmpty()) {
                return@withLock latest
            }
            val current = update(store.prepareWrite())
            store.write(previous = lastWritten.takeIf { existsInDatabase }, current = current)
            lastWritten = current
            existsInDatabase = true
            current
        }
    }

    /**
     * 只更新部分列的元数据：改内存，再按列落库，不重写消息节点。
     * 修改和落库都在 [flush] 的锁内进行，落库的先后与内存修改的先后一致。
     */
    internal suspend fun updateMetadata(
        transform: (Conversation) -> Conversation,
        persist: suspend (Conversation) -> Unit,
    ) {
        writeMutex.withLock {
            val (before, updated) = synchronized(this) {
                val before = _state.value
                before to update(transform)
            }
            persist(updated)
            // 改之前内存和数据库一致，按列写完两边又一致了，回收时不必整份重写；
            // 之后如果又有别的修改，内存已经不是 updated 这个实例，仍然算作没落库
            if (existsInDatabase && lastWritten === before) lastWritten = updated
        }
    }

    // 失败和取消也必须保存已收到的内容，且保存完成前不能释放生成任务。
    suspend fun finishGeneration(): Conversation = withContext(NonCancellable) {
        val replyId = replyMessageId.value
        update { current ->
            // 只整理本轮回复所在的节点（W-058）：收尾思考；小鲸鱼再按行切成多个气泡（一个气泡一个 MessageNode），
            // 静默时删掉。只重排 assistant 消息自己的 parts，不动任何表结构。
            WhaleBubbleSlicer.splitReplyNodes(current.copy(updateAt = Instant.now()), replyId) { it.hasVisibleContent() }
        }
        flush()
    }

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 最近一轮正在写的助手消息，这一轮还没有输出时为空；切片前按 id 找它
    val replyMessageId = MutableStateFlow<Uuid?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    private val activeJobs = mutableSetOf<Job>()
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean
        get() = refCount.get() > 0 || _generationJob.value != null || isRetained()

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    internal fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
    }

    internal fun release(): Int = refCount.decrementAndGet().also {
        if (it <= 0) scheduleIdleCheck()
    }

    @Synchronized
    fun setJob(job: Job?, cancelPrevious: Boolean = true) {
        val previous = _generationJob.value
        _generationJob.value = job
        if (cancelPrevious) previous?.cancel()
        if (job != null) activeJobs.add(job)
        job?.invokeOnCompletion { cause ->
            synchronized(this) {
                activeJobs.remove(job)
                // Also propagate cancellation when a queued coroutine never entered its body.
                if (!cancelPrevious && cause is CancellationException) previous?.cancel()
                // A replaced job must not clear or advance its successor.
                if (_generationJob.compareAndSet(job, null)) {
                    onGenerationFinished(id, cause)
                    if (refCount.get() <= 0) scheduleIdleCheck()
                }
            }
        }
        job?.start()
    }

    fun getJob(): Job? = _generationJob.value

    /**
     * 这个会话空闲（没有生成任务）时才启动 [create] 出来的任务，否则返回 null。
     *
     * R2b：不再看「是否在等工具审批」。她没有需要审批的工具（所有工具的 needsApproval 都是默认的 false），界面也没有审批入口；
     * 老数据里万一留着一条 Pending 的工具调用，以前会让这里永远返回 null（闹钟一直顺延、批次一直不发）。
     * 这种调用在组装请求前由 withoutUnresumableToolCalls 剔掉。
     *
     * 检查和 [setJob] 在同一把锁里（和 `ChatService` 里的 `synchronized(session)` 是同一个锁），
     * 所以两个触发几乎同时到来时只会有一个启动，也绝不会取消正在进行的任务（W-012）。
     */
    @Synchronized
    fun startJobIfIdle(create: () -> Job): Job? {
        if (_generationJob.value != null) return null
        val job = create()
        setJob(job, cancelPrevious = false)
        return job
    }

    @Synchronized
    fun cancelJobs(): List<Job> = activeJobs.toList().also { jobs ->
        // 先取消排在后面的任务，免得前一个结束时又把后一个拉起来
        jobs.asReversed().forEach { it.cancel() }
    }

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (!isInUse) {
                // 回收前把只改了内存的内容落库；落库失败就留在内存里，等下次释放引用或生成结束时再检查。
                // 还没落过库的会话不在这里新建：只打开过、没发过消息的新会话不该留下记录
                try {
                    if (existsInDatabase) flush()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist conversation $id before eviction", e)
                    return@launch
                }
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    @Synchronized
    fun cleanup() {
        _generationJob.value = null
        cancelJobs()
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}
