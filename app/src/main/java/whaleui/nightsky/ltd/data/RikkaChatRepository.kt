package whaleui.nightsky.ltd.data

import android.content.Context
import android.net.Uri
import java.io.File
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isEmptyInputMessage
import me.rerere.rikkahub.BuildConfig
import me.rerere.rikkahub.data.model.Conversation
import whaleui.nightsky.ltd.domain.chat.ChatEvent
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.ChatRepository
import whaleui.nightsky.ltd.domain.chat.MessageIds
import whaleui.nightsky.ltd.domain.chat.MessageStatus
import whaleui.nightsky.ltd.domain.chat.ReplyPreview
import whaleui.nightsky.ltd.domain.chat.StickerMessage
import whaleui.nightsky.ltd.sticker.firstStickerPart
import whaleui.nightsky.ltd.sticker.stickerDescriptionOrNull
import whaleui.nightsky.ltd.sticker.stickerDetailOrNull
import whaleui.nightsky.ltd.sticker.stickerIdOrNull
import whaleui.nightsky.ltd.sticker.stickerMetadata
import whaleui.nightsky.ltd.sticker.stickerUrlAlive
import whaleui.nightsky.ltd.sticker.stickerUrls
import whaleui.nightsky.ltd.data.uploads.ChatUploads
import whaleui.nightsky.ltd.data.uploads.UploadMime
import whaleui.nightsky.ltd.whale.WhaleSecrets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 聊天数据源，由 RikkaHub 的 `ChatService`（经 [WhaleChatBackend]）+ `ConversationRepository` 支撑。
 *
 * 界面（`ChatScreen` / `ChatMessages` / `ChatViewModel`）完全不知道 RikkaHub 的存在：
 * 它只认 [ChatRepository] 这一层，我们在这里把
 * `Conversation.currentMessages: List<UIMessage>` 映射成 `List<ChatMessage>`。
 *
 * 阶段 1 的映射范围（任务书 1.3）：fromHer、文字、时间、dayLabel、状态、回复引用。
 * 她的语音和图片在阶段 7 才真正产出，这里先把 Image / Audio 的映射位留好。
 *
 * 注意：我们**只把她的消息**通过 [ChatEvent.Added] 推给界面。他发的那条界面已经先乐观地插进列表了，
 * 再从数据库推一遍会重复。
 */
class RikkaChatRepository(
    private val backend: WhaleChatBackend,
    /** 调试命令：只有 debug 包里有实现，其它构建类型永远不是命令 */
    private val debugCommands: DebugChatCommands,
    /** 读附件、复制上传文件用的调度器；测试里换成当前调度器 */
    private val io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
    /** 兜底复制还是 content:// 的附件（F5） */
    private val uploads: ChatUploads,
) : ChatRepository {

    /**
     * 已经通过发送流程发给界面、但还没等到一次发送流程正常收尾的她的消息（界面上显示的内容）。
     * 跨发送流程保留：她回到一半他又发消息时，旧流程被取消，新流程接着更新这些气泡、在切片后把它们换掉。
     */
    private val inFlight = LinkedHashMap<Uuid, ChatMessage>()

    /** UIMessage.id ↔ 界面用的 Long id。进程内稳定即可，不需要持久化。 */
    // 界面 id 由消息 Uuid 确定性推出（见 MessageIds）：跨进程稳定，不会因为并发或重复节点撞车


    override suspend fun loadInitial(conversationId: Long): List<ChatMessage> {
        val uuid = backend.conversationId()
        val conversation = backend.getConversation(uuid)
        // 去重：同一条消息被映射两次会让界面的 LazyColumn key 冲突而崩
        val mapped = conversation?.currentMessages?.flatMap { it.toChatMessages() }
            ?.distinctBy { it.id }
            .orEmpty()
        debugLog(
            {
                "loadInitial uuid=$uuid conversation=${conversation != null} nodes=${conversation?.messageNodes?.size} " +
                    "current=${conversation?.currentMessages?.size} mapped=${mapped.size}"
            },
        )
        return mapped
    }

    /**
     * RikkaHub 一次性把整个会话读进内存（`ConversationSession` 就是它的分页实现），
     * 所以这里没有「更早的一页」。
     */
    override suspend fun loadOlder(conversationId: Long): List<ChatMessage> = emptyList()

    /**
     * 长按删除落库：界面 id 是 `MessageIds.forUuid(UIMessage.id)` 算出来的，
     * 这里反查回 UIMessage 的 Uuid，再让 ChatService 把**所有节点里**的副本一起删掉
     * （只删第一个命中节点的话，重启后消息会从别的节点冒出来）。
     */
    override suspend fun deleteMessage(conversationId: Long, messageId: Long) {
        val uuid = backend.conversationId()
        val conversation = backend.getConversation(uuid) ?: return
        val target = conversation.messageNodes
            .asSequence()
            .flatMap { node -> node.messages.asSequence() }
            .firstOrNull { message -> MessageIds.forUuid(message.id) == messageId }
            ?: return
        backend.deleteMessageEverywhere(uuid, target.id)
        debugLog({ "deleteMessage ui=$messageId uuid=${target.id}" })
    }

    override suspend fun deleteMessagePart(conversationId: Long, messageId: Long, bubble: ChatMessage) {
        val uuid = backend.conversationId()
        val conversation = backend.getConversation(uuid) ?: return
        val target = conversation.messageNodes
            .asSequence()
            .flatMap { node -> node.messages.asSequence() }
            .firstOrNull { message -> MessageIds.forUuid(message.id) == messageId }
            ?: return
        val remaining = withoutBubble(target.parts, bubble)
        if (remaining.size == target.parts.size) return
        if (remaining.isEmptyInputMessage()) {
            backend.deleteMessageEverywhere(uuid, target.id)
        } else {
            backend.replaceMessageParts(uuid, target.id, remaining)
        }
        debugLog({ "deleteMessagePart ui=$messageId uuid=${target.id} left=${remaining.size}" })
    }

    /** 仅 debug 的诊断日志；JVM 单测里 android.util.Log 是桩，调用会抛，所以包一层 */
    private fun debugLog(message: () -> String) {
        if (BuildConfig.DEBUG) runCatching { android.util.Log.i("WhaleChat", message()) }
    }

    override fun send(conversationId: Long, outgoing: List<ChatMessage>): Flow<ChatEvent> = channelFlow {
        // 交给 ChatService 这一步不能被取消：他连按两次发送时，第一次的发送流程会被第二次取消，
        // 如果这时第一条还没入库，它就丢了（F3 遗留）。从准备部件到 sendMessage 整段放进 NonCancellable，
        // 取消只作用于后面「等她回」的部分。
        val submission = withContext(NonCancellable) { submit(outgoing) }
        val uuid = submission.conversationId
        val parts = submission.parts
        val rawText = submission.rawText

        // ---------------- debug 通路：调试命令（实现在 src/debug，release 里没有） ----------------
        // 他和她的这一轮一次性写完，保证 node_index 顺序正确（sendMessage 是异步落库的）
        if (submission.debug) {
            val turn = debugCommands.reply(rawText) ?: return@channelFlow
            backend.appendDebugTurn(uuid, parts, turn.bubbles)
            outgoing.forEach { channel.send(ChatEvent.StatusChanged(it.id, MessageStatus.Sent)) }
            if (turn.actLikeReply) {
                outgoing.maxOfOrNull { it.id }?.let { channel.send(ChatEvent.ConversationRead(it)) }
                if (turn.bubbles.isNotEmpty()) {
                    channel.send(ChatEvent.Typing(true))
                    delay(200)
                    channel.send(ChatEvent.Typing(false))
                }
            }
            val nodes = backend.getConversation(uuid)?.messageNodes.orEmpty()
            nodes.takeLast(turn.bubbles.size).forEach { node ->
                node.currentMessage?.toChatMessage()?.let { channel.send(ChatEvent.Added(it)) }
            }
            return@channelFlow
        }

        // ---------------- 真实路径 ----------------
        // 基线是「发送前库里已有的消息 id」，不是节点下标：发送途中有删除落库、节点被切片或去重，
        // 下标都会挪（W-025）。上一个发送流程被取消时已经发给界面、还在写的她的气泡不算基线，
        // 由这个流程接着更新、切片后替换（W-004）。
        val baseline = submission.baseline

        suspend fun publish(conversation: Conversation) {
            val events = synchronized(inFlight) { diffAgainstShown(conversation.currentMessages, baseline) }
            events.forEach { channel.send(it) }
        }

        val jobFlow = backend.generationJobFlow(uuid)
        // 会话是 StateFlow：入库之后才开始收也不会漏掉变化
        val collector = launch {
            backend.conversationFlow(uuid).collect { publish(it) }
        }
        val storedId = submission.storedId
        // 乐观气泡对上库里的消息，之后补显（syncNewMessages）、删除才认得它（W-003）。
        // 先发 IdAssigned 再发 Sent：界面收齐 Sent 就认为这批已经交出去了，可以取消这个流程（F5）
        if (storedId != null && outgoing.isNotEmpty()) {
            channel.send(ChatEvent.IdAssigned(outgoing.map { it.id }, MessageIds.forUuid(storedId)))
        }
        outgoing.forEach { channel.send(ChatEvent.StatusChanged(it.id, MessageStatus.Sent)) }

        // 她本来就在回：先等她这一轮说完，再等「回答这一批」的那一轮
        if (jobFlow.first() != null) {
            withTimeoutOrNull(REPLY_WAIT_MS) { jobFlow.first { it == null } }
        }

        // round5 3.7：他发出后最多等 7 秒窗口 + 送达余量，她才开始回
        val started = withTimeoutOrNull(BATCH_START_WAIT_MS) { jobFlow.first { it != null } } != null
        if (!started) {
            channel.send(ChatEvent.Typing(false))
            collector.cancel()
            backend.getConversation(uuid)?.let { publish(it) }
            synchronized(inFlight) { inFlight.clear() }
            return@channelFlow
        }
        // 她看到了，这批消息变成已读（双对勾）
        outgoing.maxOfOrNull { it.id }?.let { channel.send(ChatEvent.ConversationRead(it)) }
        channel.send(ChatEvent.Typing(true))
        withTimeoutOrNull(REPLY_WAIT_MS) { jobFlow.first { it == null } }
        delay(200)
        channel.send(ChatEvent.Typing(false))
        collector.cancel()
        backend.getConversation(uuid)?.let { publish(it) }
        // 这一批的回复已经全部定稿发给界面；被取消的流程不会走到这里，它的气泡留给接手的流程
        synchronized(inFlight) { inFlight.clear() }
    }

    private class Submission(
        val conversationId: Uuid,
        val parts: List<UIMessagePart>,
        val rawText: String,
        /** 发送前库里已有的消息 id（去掉上一个流程还在写的她的气泡），debug 通路为空 */
        val baseline: Set<Uuid>,
        val storedId: Uuid?,
        /** 是调试命令：他的消息没交给模型，由 [debugCommands] 产出她这一轮 */
        val debug: Boolean = false,
    )

    /**
     * 准备部件、记下基线、把他的消息交给 ChatService。调用方用 NonCancellable 包住：
     * 一旦开始就一定做完，消息不会因为发送流程被取消而丢失。
     * **先入库**（立刻显示、进待发批次），再去等生成事件：
     * 如果放在等待之后，她正在回的时候他连发几条会丢消息（round5 实测踩到）。
     */
    private suspend fun submit(outgoing: List<ChatMessage>): Submission {
        val uuid = backend.conversationId()
        val parts = outgoing.toUIMessageParts()
        val rawText = outgoing.mapNotNull { it.text }.joinToString("\n").trim()
        if (debugCommands.isCommand(rawText)) {
            return Submission(uuid, parts, rawText, emptySet(), null, debug = true)
        }
        // 基线是「发送前库里已有的消息 id」，不是节点下标（W-025）；上一个流程被取消时还在写的她的气泡不算基线（W-004）
        val existing = backend.getConversation(uuid)?.currentMessages?.mapTo(HashSet()) { it.id }.orEmpty()
        val baseline = synchronized(inFlight) { existing - inFlight.keys }
        val storedId = backend.sendMessage(uuid, parts)
        return Submission(uuid, parts, rawText, baseline, storedId)
    }

    /**
     * 本次发送流程里，界面该收到哪些变化：基线之外她的每条可见消息，新出现的发 Added（带上它在库里前一条
     * 可见消息的界面 id，界面按这个位置插入），内容变了的发 Updated；之前发过、现在库里没有了的
     * （被切片替换的合并节点、被删掉的）发 Removed。调用方持有 [inFlight] 的锁。
     */
    private fun diffAgainstShown(messages: List<UIMessage>, baseline: Set<Uuid>): List<ChatEvent> {
        val events = mutableListOf<ChatEvent>()
        val alive = HashSet<Uuid>()
        messages.forEachIndexed { index, message ->
            if (message.role != MessageRole.ASSISTANT || message.id in baseline) return@forEachIndexed
            // 只有工具调用 / 只有 reasoning 的中间节点没有可显示的内容，直接跳过（否则会出现空气泡）
            val chatMessage = message.toChatMessage() ?: return@forEachIndexed
            alive += message.id
            when (val previous = inFlight.put(message.id, chatMessage)) {
                null -> events += ChatEvent.Added(chatMessage, afterId = previousVisibleId(messages, index))
                chatMessage -> Unit
                else -> events += ChatEvent.Updated(chatMessage)
            }
        }
        val gone = inFlight.keys.filterNot { it in alive }
        if (gone.isNotEmpty()) {
            gone.forEach { inFlight.remove(it) }
            events += ChatEvent.Removed(gone.map { MessageIds.forUuid(it) })
        }
        return events
    }

    /** [index] 之前最近一条在界面上有气泡的消息的界面 id */
    private fun previousVisibleId(messages: List<UIMessage>, index: Int): Long? {
        for (i in index - 1 downTo 0) {
            val message = messages[i]
            if (message.id in inFlight || message.hasVisibleContent()) return MessageIds.forUuid(message.id)
        }
        return null
    }

    // ---------------------------------------------------------------- 映射

    /** 他的待发消息 → RikkaHub 的消息部件。 */
    private suspend fun List<ChatMessage>.toUIMessageParts(): List<UIMessagePart> =
        withContext(io) {
            // 界面上配文是附件后面单独一个气泡，但交给模型的部件顺序不变：文字在前、附件在后
            val texts = mapNotNull { it.text?.takeIf { text -> text.isNotBlank() } }.map { UIMessagePart.Text(it) }
            texts + flatMap { message ->
                val sticker = message.sticker
                if (sticker != null) {
                    // 表情：带 metadata 的 Image 部件；请求转换阶段会换成文字（round5 3.3）
                    return@flatMap if (sticker.url == null) {
                        emptyList()
                    } else {
                        listOf(
                            UIMessagePart.Image(
                                url = sticker.url,
                                metadata = whaleui.nightsky.ltd.sticker.stickerMetadata(
                                    id = sticker.id,
                                    description = sticker.description,
                                    detail = sticker.detail,
                                ),
                            )
                        )
                    }
                }
                buildList<UIMessagePart> {
                    message.imageUris.forEach { uri ->
                        localizeUpload(uri)?.let { add(UIMessagePart.Image(it)) }
                    }
                    message.documentName?.let { name ->
                        val url = message.documentUrl?.let { localizeUpload(it) }
                        if (url != null) {
                            add(
                                UIMessagePart.Document(
                                    url = url,
                                    fileName = name,
                                    mime = UploadMime.resolve(message.documentMime, name),
                                    metadata = message.documentSize?.let { size ->
                                        kotlinx.serialization.json.buildJsonObject {
                                            put(DOCUMENT_SIZE_KEY, kotlinx.serialization.json.JsonPrimitive(size))
                                        }
                                    },
                                )
                            )
                        }
                    }
                }
            }
        }

    /**
     * 附件 URL → AI 层能直接编码的形式。界面选中附件时已经复制成 `file://`（[ChatUploads]），这里只是兜底：
     * 还是 `content://` 的（调试入口、老代码路径）按同样的规则复制一次（UUID 文件名、20 MB 上限、图片压缩）；
     * 复制不了就丢掉这个部件，而不是带着 AI 层认不出的 URL（`FileEncoder` 只认 file:// / data: / http，
     * 其它会抛异常并被静默吃掉，模型只收到一条空的 user 消息）。
     */
    private fun localizeUpload(uri: String): String? {
        if (isDirectlyEncodable(uri)) return uri
        val result = uploads.import(uri)
        return (result as? ChatUploads.Result.Ok)?.attachment?.uri
    }

    /**
     * RikkaHub 的一条消息 → 界面上的气泡，和发送时的样子一致（F5 遗留）：他一次发的几张图 / 几个文件在库里是一条消息，
     * 界面上每个附件一个气泡，配文是最后单独一个文字气泡。拆开的气泡都带 [ChatMessage.partOf]，删一个只删它那部分；
     * 最后一个气泡用整条消息的 id（她的回复按它定位，和发送时 IdAssigned 记下的一样）。她的消息不拆。
     */
    private fun UIMessage.toChatMessages(): List<ChatMessage> {
        val whole = toChatMessage() ?: return emptyList()
        if (role != MessageRole.USER || whole.isSticker || whole.isVoice) return listOf(whole)
        val documents = parts.filterIsInstance<UIMessagePart.Document>()
        val pieces = whole.imageUris.size + documents.size + (if (whole.text != null) 1 else 0)
        if (pieces <= 1) return listOf(whole)
        val stored = MessageIds.forUuid(id)
        val base = whole.copy(
            text = null,
            imageUris = emptyList(),
            documentName = null,
            documentSize = null,
            documentUrl = null,
            documentMime = null,
            partOf = stored,
        )
        val bubbles = buildList {
            whole.imageUris.forEach { url -> add(base.copy(imageUris = listOf(url))) }
            documents.forEach { document ->
                add(
                    base.copy(
                        documentName = document.fileName,
                        documentSize = document.sizeOrNull(),
                        documentUrl = document.url,
                        documentMime = document.mime,
                    )
                )
            }
            whole.text?.let { add(base.copy(text = it)) }
        }
        return bubbles.mapIndexed { index, bubble ->
            val key = "$index:" + (bubble.imageUris.firstOrNull() ?: bubble.documentUrl.orEmpty())
            bubble.copy(id = if (index == bubbles.lastIndex) stored else MessageIds.forPart(id, key))
        }
    }

    private fun UIMessagePart.Document.sizeOrNull(): Long? =
        (metadata?.get(DOCUMENT_SIZE_KEY) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

    /** RikkaHub 的一条消息 → 界面的一条气泡（不拆）。 */
    private fun UIMessage.toChatMessage(): ChatMessage? {
        if (parts.isEmpty()) return null
        // 表情（round5 3.6）：单独一条「无气泡表情」，不再算普通图片
        val stickerPart = firstStickerPart()
        val sticker = stickerPart?.let { part ->
            val url = (part as? UIMessagePart.Image)?.url.orEmpty()
            StickerMessage(
                id = part.stickerIdOrNull().orEmpty(),
                url = url.takeIf { stickerUrlAlive(it) },
                description = part.stickerDescriptionOrNull().orEmpty(),
                detail = part.stickerDetailOrNull().orEmpty(),
            )
        }
        // 工具输出里的语音/图片也要认（text_to_speech / draw 的结果嵌在 Tool.output 里）
        val media = collectDisplayMedia(parts)
        val stickerMediaUrls = parts.stickerUrls()
        val images = media.images.filterNot { it in stickerMediaUrls }
        // 没有任何可见内容（只有 tool 调用 / reasoning）的节点不生成气泡
        if (!hasVisibleContent()) return null
        val text = parts.filterIsInstance<UIMessagePart.Text>()
            .joinToString("\n") { it.text }
            .takeIf { it.isNotBlank() }
        val documents = parts.filterIsInstance<UIMessagePart.Document>()
        val audio = media.audio

        // 阶段 7：语音条从 Audio 部件的 metadata 里取时长（text_to_speech 写进去的）
        val waveform: List<Float> = audio?.metadata?.get("waveform")
            ?.let { element ->
                runCatching {
                    (element as kotlinx.serialization.json.JsonArray).mapNotNull { item ->
                        (item as kotlinx.serialization.json.JsonPrimitive).content.toFloatOrNull()
                    }
                }.getOrNull()
            }
            .orEmpty()
        val voiceSeconds = audio?.metadata?.get("durationMs")
            ?.let { runCatching { it.toString().trim('"').toDouble() }.getOrNull() }
            ?.let { ((it + 999) / 1000).toInt() }

        val local = createdAt
        return ChatMessage(
            id = MessageIds.forUuid(id),
            fromHer = role == MessageRole.ASSISTANT,
            text = text,
            imageUris = images,
            voiceSeconds = voiceSeconds,
            voiceUrl = audio?.url,
            voiceText = audio?.let { spokenTextOf(parts, it) },
            voiceLost = audio?.let { voiceFileMissing(it.url) } ?: false,
            waveform = waveform,
            // 一次发了几个文件时库里是一条消息、重启后是一个气泡：名字写成「a.pdf 等 2 个文件」，大小相加
            documentName = documents.firstOrNull()?.fileName?.let { first ->
                if (documents.size > 1) "$first 等 ${documents.size} 个文件" else first
            },
            documentSize = documents.mapNotNull { it.sizeOrNull() }.takeIf { it.isNotEmpty() }?.sum(),
            documentUrl = documents.firstOrNull()?.url,
            documentMime = documents.firstOrNull()?.mime,
            time = "%02d:%02d".format(local.hour, local.minute),
            dayLabel = local.date.toDayLabel(),
            createdAtMillis = local.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
            status = MessageStatus.Read,
            replyTo = NO_REPLY_PREVIEW,
            sticker = sticker,
        )
    }

    private fun LocalDate.toDayLabel(): String {
        val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        return when (today.toEpochDays() - toEpochDays()) {
            0L -> "今天"
            1L -> "昨天"
            else -> "${monthNumber}月${day}日"
        }
    }

    private companion object {
        /** 文档部件 metadata 里记下的文件大小（字节），气泡上显示用 */
        const val DOCUMENT_SIZE_KEY = "size"
        /** 阶段 1 还没有「引用回复」的落库结构；阶段 2 起从消息 metadata 里取。 */
        val NO_REPLY_PREVIEW: ReplyPreview? = null

        /** 等她开始回的等待上限：7 秒批次窗口 + 送达/排队余量（round5 3.7）。 */
        const val BATCH_START_WAIT_MS: Long = 25_000L

        /** 等她这一轮说完的等待上限（真实模型最长可能几分钟）。 */
        const val REPLY_WAIT_MS: Long = 5 * 60_000L
    }
}

/**
 * 从他的一条消息里去掉某个气泡带的部件：这个气泡的图片（按 url）、文件（按 url）和它那段文字（第一处相同的文字）。
 * 一次发多张图 / 多个文件时库里是一条消息、界面上每个附件一个气泡，删一个气泡就走这里（F5）。
 */
internal fun withoutBubble(parts: List<UIMessagePart>, bubble: ChatMessage): List<UIMessagePart> {
    val images = bubble.imageUris.toSet()
    var textLeft = bubble.text?.takeIf { it.isNotBlank() }
    // 重启后的文字气泡是这条消息全部文字部件用换行拼起来的；没有哪一段单独等于它时，全部文字一起删
    val allText = textLeft != null && parts.none { it is UIMessagePart.Text && it.text == textLeft } &&
        parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text } == textLeft
    return parts.filter { part ->
        when {
            part is UIMessagePart.Text && allText -> false
            part is UIMessagePart.Image && part.url in images -> false
            part is UIMessagePart.Document && bubble.documentUrl != null && part.url == bubble.documentUrl -> false
            part is UIMessagePart.Text && textLeft != null && part.text == textLeft -> {
                textLeft = null
                false
            }
            else -> true
        }
    }
}

/**
 * 这条语音转语音之前的文字（D5）：`text_to_speech` 把去掉语气标签的原文存在 Audio 部件 metadata 的 `text` 里；
 * 没有的话退回工具调用的入参 `text`（还带着 (laughs) 这类标签，去掉）。都没有返回 null，界面只显示「语音已丢失」。
 */
internal fun spokenTextOf(parts: List<UIMessagePart>, audio: UIMessagePart.Audio): String? {
    (audio.metadata?.get("text") as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val tool = parts.filterIsInstance<UIMessagePart.Tool>().firstOrNull { tool -> tool.output.any { it === audio || it == audio } }
        ?: return null
    val input = runCatching { tool.inputAsJson() }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: return null
    return (input["text"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        ?.let { whaleui.nightsky.ltd.voice.stripEmotionTags(it) }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

/** 本地语音文件已经不在了（`file://` 指向的文件不存在或是空的）。其它形式的 url 不判断，交给播放时再说。 */
internal fun voiceFileMissing(url: String): Boolean {
    val file = whaleui.nightsky.ltd.data.uploads.fileOfUrl(url) ?: return false
    return !file.isFile || file.length() == 0L
}
