package whaleui.nightsky.ltd.ui.chat

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import whaleui.nightsky.ltd.data.uploads.ChatUploads
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import whaleui.nightsky.ltd.domain.chat.ChatEvent
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import whaleui.nightsky.ltd.domain.chat.PendingAttachment
import whaleui.nightsky.ltd.domain.chat.ReplyPreview
import whaleui.nightsky.ltd.domain.chat.StickerMessage
import whaleui.nightsky.ltd.util.runCatchingCancellable

/**
 * 聊天页状态机。UI 只读 [uiState]，所有业务动作都通过这里。
 * 换真实模型时替换 [repository]，页面组件不需要改。
 */
class ChatViewModel(
    application: Application,
    private val repository: ChatRepository,
    /** 表情库（round5 3.5） */
    private val stickerRepository: whaleui.nightsky.ltd.sticker.StickerRepository,
    /** 附件复制进私有目录（F5） */
    private val uploads: ChatUploads,
    /** 复制附件用的调度器；测试里换成测试调度器 */
    private val io: kotlin.coroutines.CoroutineContext = kotlinx.coroutines.Dispatchers.IO,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ChatUiState(isInitialLoading = true))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    /** 输入栏表情面板的数据：系列 → 该系列的静态缩略图。 */
    val stickerPacks: StateFlow<List<StickerPanelPack>> =
        kotlinx.coroutines.flow.combine(stickerRepository.observePacks(), stickerRepository.observeStickers()) { packs, stickers ->
            buildStickerPanelPacks(getApplication(), packs, stickers)
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前对话 id；切换时把上一份 UI 状态按 id 存起来，回来时原样恢复。 */
    private var currentConversationId: Long? = null
    private val conversationStates = mutableMapOf<Long, ChatUiState>()

    private val localId = AtomicLong(10_000L)
    private var sendJob: Job? = null
    private var olderJob: Job? = null
    private var loadJob: Job? = null
    private var player: MediaPlayer? = null
    private var positionJob: Job? = null
    private var playingId: Long? = null
    private var paused = false
    private var durationMs = 0L

    /**
     * 长按删除的「撤销窗口」：界面上先消失，[UNDO_DELETE_WINDOW_MS] 内点撤销就什么都不落库；
     * 窗口过了才真正调用 [ChatRepository.deleteMessage] 写库。
     * 之前只改界面状态、不写库，重启后消息会复活（2026-10-26 发现）。
     */
    private val pendingDeleteJobs = mutableMapOf<Long, Job>()

    /**
     * 他发出的消息入库后，乐观 id → 库里的界面 id（[ChatEvent.IdAssigned]）。
     * 一对一时气泡直接换成库 id（[renamedIds] 记下旧 id，迟到的事件还能找到它）；
     * 多张图一次发出时界面是多个气泡、库里一条消息，气泡保留乐观 id，[storedAliases] 记下库 id 对应的最后一个气泡。
     */
    private val renamedIds = mutableMapOf<Long, Long>()
    private val storedAliases = mutableMapOf<Long, Long>()

    /** 多个气泡对应库里同一条消息时：气泡的乐观 id → 库里的界面 id。删其中一个气泡时只删它那部分（F5） */
    private val partOf = mutableMapOf<Long, Long>()

    // ---- 「一条一条发」的显示节奏（任务书第三点五节）----
    /** 待显示的新气泡。历史消息不走这里，所以不会被重播。 */
    private val revealQueue = RevealQueue()
    private var revealJob: Job? = null

    /** 本轮是否已经显示过第一条（第一条立即出现，之后的才按节奏等）。 */
    private var turnShownFirst = false

    /** 待显示气泡在库里的前一条可见消息（界面 id）：放出来时插在它后面，找不到就放到最后 */
    private val revealAnchors = mutableMapOf<Long, Long?>()

    init {
        openConversation(1L)
    }

    /**
     * 切到指定对话。第一次进入该对话时从 [repository] 读首屏；
     * 已经看过的对话直接恢复缓存里的消息和草稿，保证“各自加载、各自保存”。
     */
    fun openConversation(conversationId: Long) {
        if (currentConversationId == conversationId) return

        sendJob?.cancel()
        olderJob?.cancel()
        loadJob?.cancel()
        resetRevealQueue()
        stopVoice()

        currentConversationId?.let { previous ->
            if (previous != conversationId) {
                conversationStates[previous] = _uiState.value.copy(
                    isHerTyping = false,
                    voice = VoicePlayback(),
                    isInitialLoading = false,
                )
            }
        }
        currentConversationId = conversationId

        val cached = conversationStates[conversationId]
        if (cached != null) {
            _uiState.value = cached
            return
        }

        _uiState.value = ChatUiState(isInitialLoading = true)
        loadJob = viewModelScope.launch {
            val initial = repository.loadInitial(conversationId)
            if (currentConversationId != conversationId) return@launch
            _uiState.value = ChatUiState(
                messages = initial,
                isInitialLoading = false,
                hasOlder = false,
            )
        }
    }

    /**
     * 重新从仓库读一遍当前对话（debug 的闹钟轮自检用：
     * 闹钟轮是后台加的节点，界面不会自动收到事件）。
     */
    fun reload() {
        val conversationId = currentConversationId ?: return
        resetRevealQueue()
        conversationStates.remove(conversationId)
        loadJob?.cancel()
        _uiState.value = ChatUiState(isInitialLoading = true)
        loadJob = viewModelScope.launch {
            val initial = repository.loadInitial(conversationId)
            if (currentConversationId != conversationId) return@launch
            _uiState.value = ChatUiState(messages = initial, isInitialLoading = false, hasOlder = false)
        }
    }

    fun onDraftChange(text: String) {
        _uiState.update { it.copy(draft = text) }
    }

    fun addAttachments(attachments: List<PendingAttachment>) {
        if (attachments.isEmpty()) return
        _uiState.update { state ->
            val merged = (state.pendingAttachments + attachments)
                .distinctBy { it.id }
                .take(MAX_ATTACHMENTS)
            state.copy(pendingAttachments = merged)
        }
    }

    /**
     * 选中的图片 / 文件（content:// 或 file://）：先复制进 App 私有目录（图片顺便压缩），再放进待发附件（F5，W-033 / W-039）。
     * 太大、格式不支持、读不出来的不加，并通过 [ChatUiState.notice] 提示原因。
     */
    fun attach(uris: List<String>) {
        if (uris.isEmpty()) return
        val room = (MAX_ATTACHMENTS - _uiState.value.pendingAttachments.size).coerceAtLeast(0)
        viewModelScope.launch {
            val results = withContext(io) { uris.take(room).map { uploads.import(it) } }
            addAttachments(results.filterIsInstance<ChatUploads.Result.Ok>().map { it.attachment })
            val notice = buildList {
                addAll(results.filterIsInstance<ChatUploads.Result.Rejected>().map { it.reason })
                if (uris.size > room) add("一次最多 $MAX_ATTACHMENTS 个附件")
            }.joinToString("\n")
            if (notice.isNotEmpty()) _uiState.update { it.copy(notice = notice) }
        }
    }

    fun consumeNotice() {
        _uiState.update { if (it.notice == null) it else it.copy(notice = null) }
    }

    fun removeAttachment(id: String) {
        val removed = _uiState.value.pendingAttachments.filter { it.id == id }
        _uiState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.filterNot { it.id == id })
        }
        // 复制出来、还没发出去的文件一起删掉
        if (removed.isNotEmpty()) {
            viewModelScope.launch { withContext(io) { removed.forEach { uploads.discard(it.uri) } } }
        }
    }

    fun replyTo(message: ChatMessage) {
        _uiState.update {
            it.copy(
                replyTo = ReplyPreview(
                    messageId = message.id,
                    // 她的那一侧在回复条上显示当前昵称（ChatInputBar 读 LocalHerProfile）
                    author = if (message.fromHer) "她" else "我",
                    text = message.previewText,
                ),
            )
        }
    }

    fun cancelReply() {
        _uiState.update { it.copy(replyTo = null) }
    }

    fun send() {
        // 打断规则：用户发了新消息，剩下没放完的气泡立即全部显示
        flushRevealNow()
        val state = _uiState.value
        val conversationId = currentConversationId ?: return
        val text = state.draft.trim()
        if (text.isEmpty() && state.pendingAttachments.isEmpty()) return

        val reply = state.replyTo
        val now = nowTime()
        val sentAt = System.currentTimeMillis()
        val outgoing = if (state.pendingAttachments.isEmpty()) {
            listOf(
                ChatMessage(
                    id = localId.incrementAndGet(),
                    fromHer = false,
                    text = text,
                    time = now,
                    createdAtMillis = sentAt,
                    status = MessageStatus.Sending,
                    replyTo = reply,
                ),
            )
        } else {
            // 每个附件一个气泡，配文放在最后单独一个文字气泡（重启后从库里读出来也是这个样子）
            val attachments = state.pendingAttachments.mapIndexed { index, attachment ->
                ChatMessage(
                    id = localId.incrementAndGet(),
                    fromHer = false,
                    imageUris = if (attachment.isImage) listOf(attachment.uri) else emptyList(),
                    documentName = if (attachment.isImage) null else attachment.name,
                    documentSize = if (attachment.isImage) null else attachment.size,
                    documentUrl = if (attachment.isImage) null else attachment.uri,
                    documentMime = if (attachment.isImage) null else attachment.mimeType,
                    time = now,
                    createdAtMillis = sentAt,
                    status = MessageStatus.Sending,
                    replyTo = if (index == 0) reply else null,
                )
            }
            val caption = text.takeIf { it.isNotBlank() }?.let {
                ChatMessage(
                    id = localId.incrementAndGet(),
                    fromHer = false,
                    text = it,
                    time = now,
                    createdAtMillis = sentAt,
                    status = MessageStatus.Sending,
                )
            }
            attachments + listOfNotNull(caption)
        }

        val draftAtSend = state.draft
        _uiState.update {
            it.copy(
                messages = it.messages + outgoing,
                pendingAttachments = emptyList(),
                replyTo = null,
                isHerTyping = false,
            )
        }
        // Telegram 在发送过渡开始约 200ms 后才清空输入框。
        viewModelScope.launch {
            delay(200)
            _uiState.update { current ->
                if (currentConversationId == conversationId && current.draft == draftAtSend) {
                    current.copy(draft = "")
                } else {
                    current
                }
            }
        }

        startSend(conversationId, outgoing)
    }

    /**
     * 从表情面板发一张表情（round5 3.5：点一下 = 直接发送）。
     *
     * 界面先乐观插入一个表情气泡，再交给 [ChatRepository.send]；
     * 和文字/图片一样进「待发批次」，由 ChatService 那边等 7 秒合并成一次请求。
     */
    fun sendSticker(item: StickerPanelItem) {
        val conversationId = currentConversationId ?: return
        flushRevealNow()
        val message = ChatMessage(
            id = localId.incrementAndGet(),
            fromHer = false,
            time = nowTime(),
            createdAtMillis = System.currentTimeMillis(),
            status = MessageStatus.Sending,
            sticker = StickerMessage(
                id = item.id,
                url = item.fileUrl,
                description = item.description,
                detail = item.detail,
            ),
        )
        _uiState.update { it.copy(messages = it.messages + message) }
        startSend(conversationId, listOf(message))
    }

    /**
     * 一次发送的流程。[submitted] 在这批消息交给仓库入库之后完成（收齐每条的 Sent，或者流程结束）。
     *
     * 新的一次发送要取消上一次的流程（只剩「等她回」的部分，交给新流程接手），但**必须等上一次先入库**：
     * 连按两次发送时，第一次的流程如果在入库前被取消，第一条就丢了（F3 遗留）。
     * 等待期间旧流程照常收事件，所以它的 IdAssigned / Sent 不会漏。
     */
    private class SendTurn(val job: Job, val submitted: CompletableDeferred<Unit>)

    private var currentTurn: SendTurn? = null

    private fun startSend(conversationId: Long, outgoing: List<ChatMessage>) {
        val previous = currentTurn
        val submitted = CompletableDeferred<Unit>()
        val waitingSent = outgoing.mapTo(HashSet()) { it.id }
        val job = viewModelScope.launch {
            try {
                previous?.submitted?.await()
                previous?.job?.cancel()
                repository.send(conversationId, outgoing).collect { event ->
                    if (currentConversationId == conversationId) apply(event)
                    if (event is ChatEvent.StatusChanged && event.status != MessageStatus.Sending) {
                        // 仓库发 Sent 时用乐观 id 或库 id 都算
                        waitingSent.removeAll { it == event.messageId || renamedIds[it] == event.messageId }
                        if (waitingSent.isEmpty()) submitted.complete(Unit)
                    }
                }
            } finally {
                submitted.complete(Unit)
                if (currentConversationId == conversationId && currentTurn?.submitted === submitted) {
                    _uiState.update { if (it.isHerTyping) it.copy(isHerTyping = false) else it }
                }
            }
        }
        currentTurn = SendTurn(job, submitted)
        sendJob = job
    }

    // ------------------------------------------------------------ 显示节奏
    /**
     * 按节奏把待发队列里的气泡一条一条放进消息列表。
     *
     * - 本轮第一条：立即显示；
     * - 之后每条：按 [MessagePacing] 算出等待时长（字数越多等越久），等待期间顶栏显示输入状态；
     * - 队列被清空（打断 / 切页 / 后台）时自然结束。
     */
    private fun startRevealIfNeeded() {
        if (revealQueue.isEmpty) return
        if (revealJob?.isActive == true) return
        revealJob = viewModelScope.launch {
            while (isActive) {
                val next = revealQueue.peek() ?: break
                val wait = MessagePacing.delayFor(next, isFirstInTurn = !turnShownFirst)
                if (wait > 0L) {
                    // 等待期间显示「正在输入…」/「正在录音…」/「正在发送图片…」
                    _uiState.update {
                        it.copy(isHerTyping = true, typingLabel = MessagePacing.statusFor(next))
                    }
                    delay(wait)
                }
                // 等待期间它可能被切片替换或删掉，那就跳过
                if (!revealQueue.contains(next.id)) continue
                revealQueue.removeFirst()
                turnShownFirst = true
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages.withRevealed(next),
                        isHerTyping = revealQueue.isNotEmpty(),
                        typingLabel = null,
                    )
                }
                revealAnchors.remove(next.id)
            }
            _uiState.update { it.copy(isHerTyping = false, typingLabel = null) }
        }
    }

    /**
     * 打断：把队列里剩下的气泡一次性全部显示。
     *
     * 触发场景（任务书第三点五节）：用户发了新消息、离开聊天页、App 退到后台。
     */
    fun flushRevealNow() {
        revealJob?.cancel()
        revealJob = null
        val rest = revealQueue.flush()
        _uiState.update { state ->
            val merged = rest.fold(state.messages) { messages, message -> messages.withRevealed(message) }
            state.copy(messages = merged, isHerTyping = false, typingLabel = null)
        }
        revealAnchors.clear()
        turnShownFirst = false
    }

    /** App 退到后台 / 离开聊天页时调用：剩下的立即算作已显示，回来时不重播。 */
    fun onAppBackgrounded() {
        if (revealQueue.isNotEmpty()) flushRevealNow()
    }

    /**
     * 主动消息（闹钟轮）不会经过 [ChatRepository.send] 的事件流，所以由界面层在
     * `ChatGenerationEnded` 时调一次：把库里新出现的消息补进列表。
     *
     * @param animated 前台时按节奏一条条显示；后台/刚回来时直接全部可见。
     */
    fun syncNewMessages(animated: Boolean) {
        val conversationId = currentConversationId ?: return
        viewModelScope.launch {
            val latest = repository.loadInitial(conversationId)
            if (currentConversationId != conversationId) return@launch
            val shown = _uiState.value.messages.map { it.id }.toSet()
            // 只补她的消息：他的每一条都已经乐观显示过了（库里的那份 id 不同，补进来就是重复气泡，W-003）
            val fresh = latest.filter {
                it.fromHer && it.id !in shown && it.id !in storedAliases && !revealQueue.contains(it.id)
            }
            if (fresh.isEmpty()) return@launch
            // 按库里的顺序插：她这一轮的气泡排在他这期间新发的消息前面
            val previous = latest.zipWithNext().associate { (before, after) -> after.id to before.id }
            fresh.forEach { revealAnchors[it.id] = previous[it.id] }
            if (animated) {
                turnShownFirst = false
                revealQueue.enqueue(fresh)
                startRevealIfNeeded()
            } else {
                _uiState.update { state ->
                    state.copy(messages = fresh.fold(state.messages) { messages, message -> messages.withRevealed(message) })
                }
                fresh.forEach { revealAnchors.remove(it.id) }
            }
        }
    }

    /** 把一条待显示的气泡放进列表：已经在就原地替换，否则插在它的前一条后面（找不到就放到最后） */
    private fun List<ChatMessage>.withRevealed(message: ChatMessage): List<ChatMessage> {
        if (any { it.id == message.id }) return map { if (it.id == message.id) message else it }
        val anchor = revealAnchors[message.id]?.let { storedAliases[it] ?: it }
        val at = anchor?.let { id -> indexOfFirst { it.id == id } } ?: -1
        return if (at < 0) this + message else toMutableList().apply { add(at + 1, message) }
    }

    private fun resetRevealQueue() {
        revealJob?.cancel()
        revealJob = null
        revealQueue.clear()
        revealAnchors.clear()
        turnShownFirst = false
    }
    private fun apply(event: ChatEvent) {
        when (event) {
            // 新气泡先进待发队列：生成过程中不显示半句，等本轮结束再一条一条放出来
            is ChatEvent.Added -> {
                if (_uiState.value.messages.none { it.id == event.message.id } && !revealQueue.contains(event.message.id)) {
                    revealAnchors[event.message.id] = event.afterId
                    revealQueue.enqueue(listOf(event.message))
                }
                startRevealIfNeeded()
            }

            is ChatEvent.Updated -> {
                if (revealQueue.contains(event.message.id)) {
                    revealQueue.update(event.message)
                } else {
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages.map {
                                if (it.id == event.message.id) event.message else it
                            },
                        )
                    }
                }
            }

            is ChatEvent.Removed -> {
                revealQueue.remove(event.messageIds)
                _uiState.update { state ->
                    if (event.messageIds.isEmpty()) {
                        state
                    } else {
                        val gone = event.messageIds.toSet()
                        state.copy(messages = state.messages.filterNot { it.id in gone })
                    }
                }
            }

            is ChatEvent.StatusChanged -> _uiState.update { state ->
                val id = renamedIds[event.messageId] ?: event.messageId
                state.copy(
                    messages = state.messages.map {
                        if (it.id == id) it.copy(status = event.status) else it
                    },
                )
            }

            is ChatEvent.IdAssigned -> assignStoredId(event.localIds, event.messageId)

            is ChatEvent.Typing -> {
                if (!event.typing) {
                    // 本轮生成结束：开始按节奏放气泡；队列没放完之前顶栏一直显示输入状态
                    startRevealIfNeeded()
                    _uiState.update {
                        it.copy(isHerTyping = revealQueue.isNotEmpty(), typingLabel = null)
                    }
                } else {
                    turnShownFirst = false
                    _uiState.update { it.copy(isHerTyping = true, typingLabel = null) }
                }
            }

            is ChatEvent.ConversationRead -> _uiState.update { state ->
                state.copy(
                    messages = state.messages.map {
                        if (!it.fromHer && it.status != MessageStatus.Read) {
                            it.copy(status = MessageStatus.Read)
                        } else {
                            it
                        }
                    },
                )
            }
        }
    }

    private fun assignStoredId(localIds: List<Long>, storedId: Long) {
        val single = localIds.singleOrNull()
        if (single == null) {
            localIds.lastOrNull()?.let { storedAliases[storedId] = it }
            localIds.forEach { partOf[it] = storedId }
            return
        }
        renamedIds[single] = storedId
        _uiState.update { state ->
            val alreadyShown = state.messages.any { it.id == storedId }
            state.copy(
                messages = if (alreadyShown) {
                    state.messages.filterNot { it.id == single }
                } else {
                    state.messages.map { if (it.id == single) it.copy(id = storedId) else it }
                },
                replyTo = state.replyTo?.let { if (it.messageId == single) it.copy(messageId = storedId) else it },
            )
        }
    }

    fun loadOlder() {
        val state = _uiState.value
        val conversationId = currentConversationId ?: return
        if (state.isLoadingOlder || !state.hasOlder) return
        olderJob?.cancel()
        olderJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingOlder = true) }
            val older = repository.loadOlder(conversationId)
            if (currentConversationId != conversationId) return@launch
            _uiState.update {
                val existing = it.messages.mapTo(HashSet()) { message -> message.id }
                val fresh = older.filterNot { message -> message.id in existing }
                it.copy(
                    messages = fresh + it.messages,
                    isLoadingOlder = false,
                    hasOlder = older.isNotEmpty(),
                )
            }
        }
    }

    fun delete(message: ChatMessage) {
        if (playingId == message.id) stopVoice()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.filterNot { it.id == message.id },
                replyTo = if (state.replyTo?.messageId == message.id) null else state.replyTo,
            )
        }
        // 撤销窗口过了再落库；这之前撤销只撤界面，数据库里消息一直没被动过
        val conversationId = currentConversationId ?: return
        pendingDeleteJobs.remove(message.id)?.cancel()
        pendingDeleteJobs[message.id] = viewModelScope.launch {
            delay(UNDO_DELETE_WINDOW_MS)
            // 发送时记下的（乐观 id），或重启后从库里读出来时带上的
            val stored = partOf[message.id] ?: message.partOf
            if (stored != null) {
                runCatchingCancellable { repository.deleteMessagePart(conversationId, stored, message) }
                partOf.remove(message.id)
                // 她的回复按这个气泡定位；它没了就换成同一条消息里还在的另一个气泡
                if (storedAliases[stored] == message.id) {
                    val shown = _uiState.value.messages.mapTo(HashSet()) { it.id }
                    val sibling = partOf.entries.lastOrNull { it.value == stored && it.key in shown }?.key
                    if (sibling != null) storedAliases[stored] = sibling else storedAliases.remove(stored)
                }
            } else {
                runCatchingCancellable { repository.deleteMessage(conversationId, renamedIds[message.id] ?: message.id) }
            }
            pendingDeleteJobs.remove(message.id)
        }
    }

    fun undoDelete(message: ChatMessage, index: Int) {
        pendingDeleteJobs.remove(message.id)?.cancel()
        _uiState.update { state ->
            if (state.messages.any { it.id == message.id }) return@update state
            val messages = state.messages.toMutableList()
            messages.add(index.coerceIn(0, messages.size), message)
            state.copy(messages = messages)
        }
    }

    fun toggleVoice(message: ChatMessage) {
        val seconds = message.voiceSeconds ?: return
        if (playingId == message.id) {
            val media = player
            if (media != null && media.isPlaying) {
                media.pause()
                paused = true
                publishVoice(playing = false)
            } else if (media != null) {
                media.start()
                paused = false
                publishVoice(playing = true)
                startPositionUpdates()
            }
            return
        }

        if (message.voiceLost) return
        stopVoice()
        // 阶段 7：她的语音条是真的 mp3（MiniMax 合成后落在私有目录）。读不出来时不再播内置示例音频
        // （那是别人的声音，W-042），而是把气泡换成「语音已丢失」+ 转语音之前的文字（D5）
        val media = createPlayer(message)
        if (media == null) {
            markVoiceLost(message.id)
            return
        }
        player = media
        playingId = message.id
        paused = false
        durationMs = if (media.duration > 0) media.duration.toLong() else seconds * 1_000L
        media.setOnCompletionListener {
            publishVoice(playing = false, position = 0L)
            positionJob?.cancel()
        }
        media.start()
        publishVoice(playing = true, position = 0L)
        startPositionUpdates()
    }

    private fun createPlayer(message: ChatMessage): MediaPlayer? {
        val url = message.voiceUrl?.takeIf { it.isNotBlank() } ?: return null
        var created: MediaPlayer? = null
        return try {
            MediaPlayer().also { created = it }.apply {
                setDataSource(if (url.startsWith("file://")) url.removePrefix("file://") else url)
                prepare()
            }
        } catch (e: Exception) {
            created?.runCatching { release() }
            null
        }
    }

    private fun markVoiceLost(id: Long) {
        _uiState.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) it.copy(voiceLost = true) else it })
        }
    }

    private fun startPositionUpdates() {
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (isActive) {
                val media = player ?: break
                if (media.isPlaying) {
                    publishVoice(playing = true, position = media.currentPosition.toLong())
                }
                delay(60)
            }
        }
    }

    private fun publishVoice(playing: Boolean, position: Long? = null) {
        val id = playingId ?: return
        _uiState.update { state ->
            state.copy(
                voice = VoicePlayback(
                    messageId = id,
                    isPlaying = playing,
                    positionMs = position ?: state.voice.positionMs,
                    durationMs = durationMs,
                ),
            )
        }
    }

    private fun stopVoice() {
        positionJob?.cancel()
        positionJob = null
        player?.runCatching {
            if (isPlaying) pause()
            release()
        }
        player = null
        playingId = null
        paused = false
        durationMs = 0L
        _uiState.update { it.copy(voice = VoicePlayback()) }
    }

    private fun nowTime(): String =
        SimpleDateFormat("HH:mm", Locale.CHINA).format(Date())

    override fun onCleared() {
        stopVoice()
        pendingDeleteJobs.values.forEach { it.cancel() }
    }
}

/**
 * 删除的撤销窗口：比界面撤销 Bulletin 的 5000ms 稍长，保证撤销按钮还在时绝对不会提前落库。
 * 进程在这个窗口内被杀掉 = 这次删除不生效（消息还在库里），比"删了又回来"更好解释。
 */
private const val UNDO_DELETE_WINDOW_MS = 5_500L

/** 一次最多带几个附件（和相册选择器的上限一致） */
private const val MAX_ATTACHMENTS = 9
