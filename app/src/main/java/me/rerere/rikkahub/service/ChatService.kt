package me.rerere.rikkahub.service

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import me.rerere.ai.agent.AgentEvent
import me.rerere.ai.agent.AgentFinishReason
import me.rerere.ai.agent.RetryAttempt
import me.rerere.ai.agent.runAgent
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.common.android.Logging
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.ChatRequestFactory
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.event.AppEvent
import me.rerere.rikkahub.data.event.AppEventBus
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.bindConfig
import me.rerere.rikkahub.data.model.cancelInterruptedTools
import me.rerere.rikkahub.data.model.deleteMessageEverywhere
import me.rerere.rikkahub.data.model.fillModelSnapshots
import me.rerere.rikkahub.data.model.getAssistantOf
import me.rerere.rikkahub.data.model.getStoredAssistantOf
import me.rerere.rikkahub.data.model.replaceMessagePartsEverywhere
import me.rerere.rikkahub.data.model.toMessageNode
import me.rerere.rikkahub.data.model.withAssistantUpdate
import me.rerere.rikkahub.data.model.withoutConversationFields
import me.rerere.rikkahub.data.model.withoutUnresumableToolCalls
import me.rerere.rikkahub.data.repository.ConversationRepository
import whaleui.nightsky.ltd.whale.ConversationNormalizer
import whaleui.nightsky.ltd.whale.WhaleAlarmTurn
import whaleui.nightsky.ltd.whale.WhaleBatchScheduler
import whaleui.nightsky.ltd.whale.WhaleGate
import whaleui.nightsky.ltd.whale.WhalePendingBatch
import whaleui.nightsky.ltd.whale.WhaleUnansweredReply
import kotlin.uuid.Uuid

private const val TAG = "ChatService"

/** 闹钟轮先等待发批次回完时，最多看几轮（每轮等一个正在跑的任务结束） */
private const val PENDING_BATCH_ROUNDS = 6

/**
 * 她那一个对话的聊天引擎：他发消息（连发合并成批次）、她回复（runTurn）、闹钟轮、删除 / 改写消息、停止生成。
 *
 * R2：原版界面删光之后，重新生成、分支切换 / fork、编辑消息、工具审批、原生消息队列、多会话管理
 * （文件夹、置顶、移动、删除 / 恢复会话）、压缩、标题 / 建议生成都没有调用方了，已经删掉。
 * 所有入口拿到的会话 id 都是她的固定对话（[whaleui.nightsky.ltd.whale.WhaleConversationProvider]）。
 */
class ChatService(
    private val context: Application,
    private val appScope: AppScope,
    private val appEventBus: AppEventBus,
    private val settingsStore: SettingsStore,
    private val conversationRepo: ConversationRepository,
    private val requestFactory: ChatRequestFactory,
    private val filesManager: FilesManager,
    /** 连发合并的计时器（round5 3.7） */
    whaleBatchScheduler: WhaleBatchScheduler,
    /** 一轮生成期间保活进程：前台服务，拉不起来时改用 expedited Worker（W-020） */
    private val keepAlive: GenerationKeepAlive = ForegroundOrWorkerKeepAlive.forContext(context),
) {
    /**
     * 连发合并的待发批次。批次要问「这个会话是不是正在生成」，答案就在 ChatService 自己这里，
     * 所以由 ChatService 用注入的计时器组装，而不是从 Koin 拿一个反过来依赖 ChatService 的单例（W-043）。
     */
    private val whaleBatch = WhalePendingBatch(
        scheduler = whaleBatchScheduler,
        isGenerating = { conversationId -> isGenerating(conversationId) },
    )

    /** 一轮生成的结果 */
    private sealed interface TurnResult {
        data class Finished(val reason: AgentFinishReason) : TurnResult

        /** 没能完成：错误已经记进日志 */
        data object Failed : TurnResult
    }

    private val conversationStore = object : ConversationStore {
        override suspend fun prepareWrite(): (Conversation) -> Conversation {
            // 会话落库即视为开始，此时把助手的配置固定到会话上
            val settings = settingsStore.awaitLoaded()
            return { it.bindConfig(settings).fillModelSnapshots(settings) }
        }

        override suspend fun write(previous: Conversation?, current: Conversation) {
            if (previous == null && !conversationRepo.existsConversationById(current.id)) {
                conversationRepo.insertConversation(current)
            } else {
                conversationRepo.updateConversation(current)
            }
            if (previous != null && previous.messageNodes !== current.messageNodes) {
                withContext(Dispatchers.IO) { deleteUnreferencedFiles(previous, current) }
            }
        }
    }

    private val sessionManager = ConversationSessionManager(
        scope = appScope,
        store = conversationStore,
        createInitialConversation = { id ->
            Conversation.ofId(id, assistantId = settingsStore.settingsFlow.value.getCurrentAssistant().id)
        },
        onGenerationFinished = ::onSessionGenerationFinished,
        // 他还有待发批次时会话不回收：7 秒到点的那一轮跑在他发消息时的同一个会话对象上（W-022）
        isRetained = { id -> whaleBatch.hasPending(id) },
    )

    /**
     * 原来是给原版聊天页显示的错误列表（没人读了）；现在只记日志。
     * 取消不是错误。
     */
    private fun reportError(error: Throwable, conversationId: Uuid?, title: String) {
        if (error is CancellationException) return
        if (BuildConfig.DEBUG) {
            Log.e(TAG, "$title (conversation=$conversationId)", error)
        } else {
            Log.e(TAG, "$title (conversation=$conversationId): ${error.brief()}")
        }
    }

    fun cleanup() = runCatching { sessionManager.cleanup() }

    private fun onSessionGenerationFinished(session: ConversationSession, @Suppress("UNUSED_PARAMETER") cause: Throwable?) {
        // round5 3.7：他在她回复期间发的内容，本该「她说完立刻发」，在这里补上触发
        appScope.launch { whaleBatch.onGenerationFinished(session.id) }
    }

    /** 按会话 id 订阅加载后的内容，会话对象被回收重建也跟得上（W-022） */
    fun getConversationFlow(conversationId: Uuid): Flow<Conversation> =
        sessionManager.getConversationFlow(conversationId)

    /** 会话的最新内容：已加载的以内存为准，否则读数据库 */
    suspend fun getConversation(conversationId: Uuid): Conversation? =
        sessionManager.get(conversationId)?.takeIf { it.isInitialized }?.state?.value
            ?: conversationRepo.getConversationById(conversationId)

    fun getGenerationJobStateFlow(conversationId: Uuid): Flow<Job?> =
        sessionManager.getGenerationJobStateFlow(conversationId)

    /** 这个会话现在是不是正在生成（她正在回）；连发批次的待发判定用（round5 3.7）。 */
    fun isGenerating(conversationId: Uuid): Boolean =
        sessionManager.get(conversationId)?.let { session ->
            session.getJob() != null || session.isGenerating
        } ?: false

    /**
     * 生成任务：期间保活进程，切到后台也能把这一轮跑完。
     *
     * 批次 7 秒到点时他可能已经切到后台，Android 12+ 这时拉前台服务会被拒绝（W-020）；
     * [keepAlive] 那时改排一个 expedited Worker 占住进程，直到这一轮结束。
     */
    private fun launchGenerationJob(conversationId: Uuid, block: suspend () -> Unit): Job =
        appScope.launch(start = CoroutineStart.LAZY) {
            val release = keepAlive.hold(conversationId)
            try {
                block()
            } finally {
                release()
            }
        }

    // ---- 加载会话 ----

    private suspend fun ensureInitialized(session: ConversationSession) {
        session.initialize(
            load = { loadConversation(session.id) },
            create = { newConversation(session.id) },
        )
    }

    /** 持有会话引用并确保已加载后执行 [block] */
    private suspend fun <T> withLoadedSession(
        conversationId: Uuid,
        block: suspend (ConversationSession) -> T,
    ): T = sessionManager.withSession(conversationId) { session ->
        ensureInitialized(session)
        block(session)
    }

    // 新建对话, 并添加预设消息
    private suspend fun newConversation(conversationId: Uuid): Conversation {
        val assistant = settingsStore.awaitLoaded().getCurrentAssistant()
        return Conversation.ofId(
            id = conversationId,
            assistantId = assistant.id,
            newConversation = true
        ).updateCurrentMessages(assistant.presetMessages)
    }

    // 引入会话配置之前创建的会话没有固定配置，加载时按助手当前的值补上，之后不再随助手变化。
    private suspend fun loadConversation(conversationId: Uuid): Conversation? {
        val conversation = conversationRepo.getConversationById(conversationId) ?: return null
        // settingsFlow 在启动初期还是占位值，固定配置必须基于真实设置
        val bound = conversation.bindConfig(settingsStore.awaitLoaded())
        if (bound !== conversation) conversationRepo.updateConversationConfig(bound)
        // 历史里遗留的「同一条消息出现在多个节点」坏数据，加载时在后台线程整体合并一次；
        // 生成结束时只查本轮那条（W-058）。合并结果随下一次落库写回
        if (!WhaleGate.isWhaleConversation(bound.id, bound.assistantId)) return bound
        return withContext(Dispatchers.Default) { ConversationNormalizer.dedupeByMessageId(bound) }
    }

    /** 加载会话后在最新状态上修改并落库 */
    private suspend fun mutate(conversationId: Uuid, transform: (Conversation) -> Conversation): Conversation =
        withLoadedSession(conversationId) { session ->
            session.update(transform)
            session.flush()
        }

    // 只删上次落库时引用、这次落库不再引用的文件；只在内存里出现过的文件宁可漏删也不误删
    private fun deleteUnreferencedFiles(previous: Conversation, current: Conversation) {
        val retainedFiles = current.files.toSet()
        val deletedFiles = previous.files.filter { it !in retainedFiles }
            // round5：表情库文件（files/stickers/...）不属于「聊天附件」，删聊天记录不能删库
            .filterNot { it.isStickerLibraryFile() }
        if (deletedFiles.isNotEmpty()) {
            filesManager.deleteChatFiles(deletedFiles)
            Log.w(TAG, "deleteUnreferencedFiles: $deletedFiles")
        }
    }

    // ---- 发送消息（连发合并，round5 3.7） ----

    /**
     * 他发出一条：**立刻入库并显示**，同时进待发批次、重新计时，停手 7 秒再合成一次请求
     * （界面仍是独立气泡，合并只发生在请求转换阶段）。
     *
     * 返回这条消息落库时的 id（界面用它把乐观插入的气泡对上库里的消息）；内容为空时返回 null。
     */
    fun sendMessage(conversationId: Uuid, content: List<UIMessagePart>): Uuid? {
        if (content.isEmptyInputMessage()) return null
        // 持有引用直到入批（W-022）：她刚说完时排着一个 5 秒后的空闲回收，getOrCreate 取消不了它，
        // 会话可能在落库之后、入批之前被回收；入批之后由待发批次留住会话（sessionManager 的 isRetained）
        val session = sessionManager.acquire(conversationId)
        val message = UIMessage(role = MessageRole.USER, parts = content)
        appScope.launch {
            try {
                ensureInitialized(session)
                session.update { current ->
                    current.copy(messageNodes = current.messageNodes + message.toMessageNode())
                }
                session.flush()
                whaleBatch.record(conversationId, message.id) {
                    triggerBatchedReply(conversationId)
                }
                // 她正在回：这一批要**她说完立刻发**，不能等剩下的 7 秒窗口（任务书 3.7 已确认的决定）
                if (session.getJob() != null) {
                    whaleBatch.markPending(conversationId)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                reportError(e, conversationId, context.getString(R.string.error_title_send_message))
            } finally {
                session.release()
            }
        }
        return message.id
    }

    /**
     * 批次计时到（或闹钟轮强制先把批次发出去）时，替这一批消息跑一轮回复。
     *
     * @return 是否真的启动了；她还在回时返回 false，批次会保留并等这一轮结束再触发（W-011）
     */
    private fun triggerBatchedReply(conversationId: Uuid): Boolean {
        val session = sessionManager.getOrCreate(conversationId)
        // 检查和 setJob 在同一把锁里，不会和闹钟轮同时启动，也不会取消别的任务（W-012）
        val job = session.startJobIfIdle {
            launchGenerationJob(conversationId = conversationId) {
                try {
                    ensureInitialized(session)
                    finishInterruptedPendingTools(session)
                    runTurn(session)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    reportError(e, conversationId, context.getString(R.string.error_title_send_message))
                }
            }
        }
        return job != null
    }

    /**
     * debug 通路（聊天框里的调试命令）：把他这条消息和她这次的若干条回复**一次性**写进会话，不经过网络。
     * 只被 `RikkaChatRepository` 的调试命令分支调用，release 构建里没有调用方。
     *
     * 必须一次写完：走 `sendMessage` 的话它是异步落库的，
     * 后续的 assistant 节点会先于 user 节点写进数据库，`node_index` 顺序就错了。
     */
    suspend fun appendDebugTurn(
        conversationId: Uuid,
        userParts: List<UIMessagePart>,
        assistantParts: List<List<UIMessagePart>>,
    ) {
        val session = sessionManager.getOrCreate(conversationId)
        ensureInitialized(session)
        session.update { current ->
            current.copy(
                messageNodes = current.messageNodes +
                    UIMessage(role = MessageRole.USER, parts = userParts).toMessageNode() +
                    assistantParts.map { parts ->
                        UIMessage(role = MessageRole.ASSISTANT, parts = parts).toMessageNode()
                    },
            )
        }
        session.flush()
    }

    // ---- 闹钟轮 ----

    /** 闹钟轮的结果（任务书 6.1）。 */
    enum class AlarmTurnResult {
        /** 真的发出去了（有非空 assistant 文本） */
        Sent,

        /** 静默：这一轮什么都没发，节点已删除 */
        Silent,

        /** 他的消息正在回：没开这一轮，由调度层用闹钟顺延 */
        Busy,

        /** 开了这一轮但生成失败（错误已记进日志）；调度层可以稍后重试 */
        Failed,
    }

    /** 所有闹钟轮串行：同一分钟的两个闹钟一个接一个跑，不会互相覆盖或取消（W-012） */
    private val alarmTurnMutex = Mutex()

    /**
     * 「助手发起一轮」（任务书 6.1）。**必须新开一个回复节点**，不能续写进上一条 assistant。
     *
     * - 进程内所有闹钟轮用 [alarmTurnMutex] 串行；
     * - 他还有没回的待发批次时，先替批次单独回一轮（不等 7 秒），等那一轮结束再开闹钟轮（W-011）；
     * - 忙碌检查和启动在同一把会话锁里（[ConversationSession.startJobIfIdle]），
     *   有任务在跑就返回 [AlarmTurnResult.Busy]，**绝不取消**正在进行的任务（W-012）；
     * - 闹钟轮的类型、说明、任务 id 跟着这一轮的请求走（`WhaleAlarmTurn` → `TransformerContext`），
     *   不再用进程级全局标记。
     *
     * 清理与分条走的是同一套：`WhaleGuardTransformer` 用闹钟轮类型组装请求，
     * `WhaleOutputTransformer` 清理，`WhaleBubbleSlicer` 在 `finishGeneration` 里切片 / 静默时删节点。
     */
    suspend fun runAlarmTurn(
        conversationId: Uuid,
        alarm: WhaleAlarmTurn,
    ): AlarmTurnResult = alarmTurnMutex.withLock {
        // 持有会话引用：等批次回复期间会话不会被空闲回收、换成另一个对象
        sessionManager.withSession(conversationId) { session -> runAlarmTurnLocked(session, alarm) }
    }

    private suspend fun runAlarmTurnLocked(
        session: ConversationSession,
        alarm: WhaleAlarmTurn,
    ): AlarmTurnResult {
        val conversationId = session.id
        Log.i(TAG, "runAlarmTurn: enter gateReady=${WhaleGate.assistantId != null}")

        if (!replyToPendingBatchFirst(session)) {
            Log.i(TAG, "runAlarmTurn: busy (pending batch could not start)")
            return AlarmTurnResult.Busy
        }
        // 他刚好又发了一条：先让批次回他，闹钟顺延
        if (whaleBatch.hasPending(conversationId)) {
            Log.i(TAG, "runAlarmTurn: busy (new batch)")
            return AlarmTurnResult.Busy
        }

        // debug 假模型（任务书 6.7）：不调模型，直接给固定文本或静默。
        // 用来在没有真实 Key 的情况下验证「新开节点 / 静默无气泡 / 通知」整条链路。
        val fake = whaleui.nightsky.ltd.debug.WhaleDebugFakeModel
        // BuildConfig.DEBUG 是编译期常量，release 里这一整块会被 R8 删掉（连类名都不留）
        if (BuildConfig.DEBUG && fake.enabled) {
            if (session.getJob() != null) {
                Log.i(TAG, "runAlarmTurn: busy")
                return AlarmTurnResult.Busy
            }
            return runFakeAlarmTurn(conversationId, fake)
        }

        var sent = false
        var failed = false
        val job = session.startJobIfIdle {
            launchGenerationJob(conversationId = conversationId) {
                try {
                    ensureInitialized(session)
                    val result = runTurn(session, alarm = alarm) { plan, finished -> sent = plan.sentIn(finished) }
                    failed = result == TurnResult.Failed
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed = true
                    reportError(e, conversationId, context.getString(R.string.error_title_send_message))
                }
            }
        }
        if (job == null) {
            Log.i(TAG, "runAlarmTurn: busy")
            return AlarmTurnResult.Busy
        }
        job.join()

        return when {
            sent -> AlarmTurnResult.Sent
            failed -> AlarmTurnResult.Failed
            else -> AlarmTurnResult.Silent
        }
    }

    /**
     * 闹钟到点时他还有没回的待发批次：先替批次单独跑一轮回复，等它结束（W-011）。
     *
     * 她正在回的话，批次会在她这一轮结束后自己触发；这里只是等这些都跑完。
     * 返回 false 表示批次没法启动，闹钟这一轮要顺延。
     */
    private suspend fun replyToPendingBatchFirst(session: ConversationSession): Boolean {
        val conversationId = session.id
        repeat(PENDING_BATCH_ROUNDS) {
            val running = session.getJob()
            if (running != null) {
                running.join()
                // 结束回调在 AppScope 上异步触发批次，让它先跑
                yield()
                return@repeat
            }
            if (!whaleBatch.hasPending(conversationId)) return true
            Log.i(TAG, "runAlarmTurn: reply to pending batch first")
            if (!whaleBatch.flushNow(conversationId) && session.getJob() == null) return false
        }
        return session.getJob() == null && !whaleBatch.hasPending(conversationId)
    }

    /**
     * 启动时补回复（W-021）：待发批次只在内存，他发完就被杀进程的话，那条消息没人回。
     * 最后一条是他最近 30 分钟内的消息、她没回过、没在生成、也不在批次里时，把它重新放进批次，照常 7 秒后回。
     *
     * 和闹钟轮共用 [alarmTurnMutex]：闹钟轮正在跑时等它结束再看（那时最后一条已经是她的话，就不补了），
     * 不会和闹钟轮抢着回同一条消息。
     *
     * @return 是否补了
     */
    suspend fun resumeUnansweredReply(conversationId: Uuid, nowMillis: Long = System.currentTimeMillis()): Boolean =
        alarmTurnMutex.withLock {
            if (isGenerating(conversationId) || whaleBatch.hasPending(conversationId)) return@withLock false
            val conversation = getConversation(conversationId) ?: return@withLock false
            val messageId = WhaleUnansweredReply.find(conversation.currentMessages, nowMillis)
                ?: return@withLock false
            Log.i(TAG, "resumeUnansweredReply: his last message has no reply yet, queue it again")
            whaleBatch.record(conversationId, messageId) { triggerBatchedReply(conversationId) }
            true
        }

    private suspend fun runFakeAlarmTurn(
        conversationId: Uuid,
        fake: whaleui.nightsky.ltd.debug.WhaleDebugFakeModel,
    ): AlarmTurnResult {
        val assistantName = settingsStore.settingsFlow.value
            .let { settings -> settings.assistants.find { it.id == settings.assistantId } }
            ?.name?.ifBlank { null }
            ?: whaleui.nightsky.ltd.domain.settings.HerProfile.DEFAULT_NICKNAME
        if (fake.mode == whaleui.nightsky.ltd.debug.WhaleDebugFakeModel.Mode.Silent) {
            Log.i(TAG, "runAlarmTurn: fake model -> silent")
            // 静默轮：按真实路径发出 contentPreview=null 的结束事件，
            // ChatNotificationManager 见到 null 会直接 return —— 这正是任务书 6.2 要求的「静默时无通知」。
            appEventBus.emit(
                AppEvent.ChatGenerationEnded(
                    conversationId = conversationId,
                    senderName = assistantName,
                    contentPreview = null,
                )
            )
            return AlarmTurnResult.Silent
        }
        appendDebugAssistantOnly(
            conversationId = conversationId,
            parts = listOf(UIMessagePart.Text(fake.fixedText)),
        )
        Log.i(TAG, "runAlarmTurn: fake model -> sent")
        appEventBus.emit(
            AppEvent.ChatGenerationEnded(
                conversationId = conversationId,
                senderName = assistantName,
                contentPreview = fake.fixedText.take(50).trim(),
            )
        )
        return AlarmTurnResult.Sent
    }

    /** 只追加一条 assistant 节点（debug 的假模型用，不落 user 消息）。 */
    private suspend fun appendDebugAssistantOnly(conversationId: Uuid, parts: List<UIMessagePart>) {
        if (parts.isEmpty()) return
        val session = sessionManager.getOrCreate(conversationId)
        ensureInitialized(session)
        session.update { current ->
            current.copy(
                messageNodes = current.messageNodes + UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = parts,
                ).toMessageNode(),
            )
        }
        session.flush()
    }

    // ---- 生成回复 ----

    /**
     * 生成一轮回复：请求模型、执行工具，直到模型不再调用工具或达到步数上限。
     *
     * 接着当前对话的末尾回复；发送的最后一条是助手消息时从它继续，回复写回那条消息。
     * 失败时记日志并返回 [TurnResult.Failed]；取消时直接抛出。
     */
    private suspend fun runTurn(
        session: ConversationSession,
        /**
         * 小鲸鱼闹钟轮：不为 null 时历史最后一条是 assistant 也**必须新开一个节点**，不能续写进上一条；
         * 并随请求交给输入转换组装闹钟轮的注入（W-012）。
         */
        alarm: WhaleAlarmTurn? = null,
        /** 生成结束、切片落库之后调用（取消和失败也会调用；生成开始前失败时不调用） */
        onEnded: (ReplyPlan, Conversation) -> Unit = { _, _ -> },
    ): TurnResult {
        val conversationId = session.id
        // 先清理上一轮状态；确定历史后，仅为真正的续写恢复消息 ID。
        session.replyMessageId.value = null
        // round5 3.7：这一轮真的开始生成了，批次里的「待触发」标记失效
        whaleBatch.onGenerationStarted(conversationId)
        // 生成开始前失败时只发一个不带预览的结束事件，用不到发送者名称
        var senderName = ""
        // 结束事件整轮只发一次：正常结束在 finally 里发（带预览），出错 / 取消时由 catch 补发（W-047）
        val end = TurnEndSignal { preview ->
            appEventBus.emit(AppEvent.ChatGenerationEnded(conversationId, senderName, preview))
        }
        try {
            val settings = settingsStore.awaitLoaded()
            // 清掉上一轮的建议；没法继续执行的工具调用发给模型会被拒绝，先去掉。
            val conversation = session.update { latest ->
                latest.copy(chatSuggestions = emptyList()).withoutUnresumableToolCalls()
            }

            val plan = conversation.planReply(forceNewNode = alarm != null)
            // 接着上一条助手消息写时就是它，否则等第一次输出。要在 createContext 之前换掉，
            // 它会挂起也可能失败，期间或失败后不能还指着上一轮的回复
            session.replyMessageId.value = plan.continuedMessageId

            val turn = requestFactory.createContext(settings, conversation, whaleAlarm = alarm)
            senderName = if (turn.assistant.useAssistantAvatar) {
                turn.assistant.name.ifEmpty { context.getString(R.string.assistant_page_default_assistant) }
            } else {
                turn.model.displayName
            }

            val (request, hooks) = requestFactory.createAgent(turn, plan.history, session.processingStatus)
            var finishReason: AgentFinishReason? = null
            try {
                runAgent(plan.applyTo(request), hooks).flowOn(Dispatchers.IO).collect { event ->
                    when (event) {
                        is AgentEvent.MessageUpdated -> session.applyReply(plan, event.message)
                        is AgentEvent.Retrying -> logRetry(event.attempt)
                        is AgentEvent.Finished -> {
                            Log.i(TAG, "runTurn: finished (${event.reason})")
                            finishReason = event.reason
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    session.processingStatus.value = null
                    // 可能被取消了，或者意外结束，兜底保存
                    val finished = session.finishGeneration()
                    onEnded(plan, finished)
                    // 生成结束：后台时发送完成通知，聊天页据此补显新消息。
                    // 只有「这一轮真的产出了一条 assistant 内容」才带预览（才通知）。
                    // 静默轮（[SILENT]）会把节点删掉，此时最后一条是他的消息 —— 绝不能拿它当预览。
                    // round5：她只发了一张表情时，预览是「[表情] description」而不是空。
                    end.ended(plan.previewIn(finished))
                }
            }

            return TurnResult.Finished(checkNotNull(finishReason))
        } catch (e: CancellationException) {
            // 生成开始前就被取消时上面的 finally 不会执行；已经发过就不再发
            end.ended(null)
            throw e
        } catch (e: Exception) {
            // 兜底发结束事件（生成开始前失败时上面的 finally 不会执行）
            end.ended(null)
            reportError(e, conversationId, context.getString(R.string.error_title_generation))
            Logging.log(TAG, "runTurn: $e")
            Logging.log(TAG, e.stackTraceToString())
            return TurnResult.Failed
        }
    }

    private fun logRetry(attempt: RetryAttempt) {
        val message = "Provider connection failed, retrying in ${attempt.delay} (${attempt.retry}/${attempt.maxRetries})"
        if (BuildConfig.DEBUG) {
            Log.w(TAG, message, attempt.cause)
        } else {
            Log.w(TAG, "$message: ${attempt.cause.brief()}")
        }
    }

    /**
     * release 的 logcat 只打异常类型和一句简短信息，不打堆栈（N-006）。
     * 完整堆栈只在 debug 的 logcat 和 App 内的日志页（[Logging]，只在进程内存里）出现。
     */
    private fun Throwable.brief(): String =
        javaClass.name + (message?.lineSequence()?.firstOrNull()?.take(160)?.let { ": $it" } ?: "")

    private suspend fun finishInterruptedPendingTools(session: ConversationSession) {
        session.tryUpdate { it.cancelInterruptedTools() }
        session.flush()
    }

    // ---- 高级设置改模型 ----

    /**
     * 高级设置对助手的修改：会话持有的字段只改当前会话，其余写回助手设置。
     *
     * @param update 接收会话视角下的助手，返回修改后的助手
     */
    suspend fun updateChatAssistant(conversationId: Uuid, update: (Assistant) -> Assistant) {
        withLoadedSession(conversationId) { session ->
            val settings = settingsStore.awaitLoaded()
            val conversation = session.state.value
            val stored = settings.getStoredAssistantOf(conversation)
            val updated = update(settings.getAssistantOf(conversation))
            val assistant = updated.withoutConversationFields(conversation, stored)
            session.updateMetadata(
                transform = { it.withAssistantUpdate(updated, settings) },
                persist = conversationRepo::updateConversationConfig,
            )
            if (assistant != stored) {
                settingsStore.update { latest ->
                    latest.copy(assistants = latest.assistants.map { if (it.id == assistant.id) assistant else it })
                }
            }
        }
    }

    // ---- 消息操作 ----

    /**
     * 删掉这条消息在**所有节点**里的副本。
     *
     * 界面消息 id 是 `UIMessage.id` 的哈希（同一条消息可能出现在多个节点里），
     * 只删第一个命中的节点的话，剩下的副本会让消息在界面上"删了又回来"。
     * 小鲸鱼的长按删除走这里，保证删除真的落库、重启后不会复活。
     */
    suspend fun deleteMessageEverywhere(conversationId: Uuid, messageId: Uuid) {
        mutate(conversationId) { conversation ->
            // 没命中任何节点时原样返回：flush 会判断出"没变化"，不会白写一次库
            conversation.deleteMessageEverywhere(messageId) ?: conversation
        }
        // round5 3.7：他在等待期间删除了批次里的某条 → 从批次移除；批次空了计时自动取消
        whaleBatch.remove(conversationId, messageId)
    }

    /**
     * 原地替换一条消息（所有节点里的副本）的内容；不再被引用的附件文件由 flush 时的 deleteUnreferencedFiles 清掉。
     * 小鲸鱼多图消息删掉其中一张图时用（F5）。
     */
    suspend fun replaceMessagePartsEverywhere(conversationId: Uuid, messageId: Uuid, parts: List<UIMessagePart>) {
        mutate(conversationId) { conversation ->
            conversation.replaceMessagePartsEverywhere(messageId, parts) ?: conversation
        }
    }

    // 停止当前会话生成任务（不清理会话缓存）
    suspend fun stopGeneration(conversationId: Uuid) {
        val session = sessionManager.get(conversationId) ?: return
        val jobs = session.cancelJobs()
        if (jobs.isEmpty()) return
        jobs.forEach { it.join() }
        if (session.isInitialized) finishInterruptedPendingTools(session)
    }
}

/**
 * 表情库文件（`files/stickers/...`）不属于「聊天附件」：删掉聊天里的一条表情消息时，
 * 不能把库里的图一起删掉（round5）。
 */
private fun android.net.Uri.isStickerLibraryFile(): Boolean =
    path?.contains("/${whaleui.nightsky.ltd.sticker.STICKER_DIR_NAME}/") == true
