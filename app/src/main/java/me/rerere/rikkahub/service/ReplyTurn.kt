package me.rerere.rikkahub.service

import me.rerere.ai.agent.AgentRequest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.Conversation
import whaleui.nightsky.ltd.data.hasVisibleContent
import whaleui.nightsky.ltd.sticker.previewTextForWhale
import kotlin.uuid.Uuid

/**
 * 一轮回复写到哪里、产出了什么。`ChatService.runTurn` 开始时由会话算一次，整轮都用它，
 * 和 Android、设置、网络无关，可以直接单测（`ReplyTurnTest`）。
 */
internal data class ReplyPlan(
    /** 交给 agent 的历史 */
    val history: List<UIMessage>,
    /** 本轮回复所在的节点；续写时是被续写消息的节点，否则是第一次输出时才建的新节点 */
    val replyNodeId: Uuid,
    /** 本轮续写的助手消息；新开一条消息时为 null */
    val continuedMessageId: Uuid?,
    /** 本轮开始时会话里已有的消息 id，用来认出本轮新产出的消息 */
    val existingMessageIds: Set<Uuid>,
    /**
     * 新节点插在这个节点后面：本轮开始时历史的最后一个节点。第一次输出之前他又发了消息时，
     * 回复仍排在它回答的消息后面、他新发的消息前面（W-008）。
     */
    val anchorNodeId: Uuid? = null,
)

/**
 * 决定本轮回复写到哪里。
 *
 * - 历史最后一条是助手消息：从它继续，回复写回那条消息；
 * - [forceNewNode]（闹钟轮）：历史最后一条是助手消息时也新开一个节点。
 *
 * R2：重新生成删掉之后，回复总是接在当前对话末尾（W-027 那种「重新生成切片后的气泡」不会再出现）。
 */
internal fun Conversation.planReply(forceNewNode: Boolean): ReplyPlan {
    val nodes = messageNodes
    val history = currentMessages
    val continueLast = history.lastOrNull()?.role == MessageRole.ASSISTANT && !forceNewNode
    // 闹钟轮不续写：她上一条消息只是历史，本轮新开一条消息（W-002）
    val replyNodeId = if (continueLast) nodes[history.lastIndex].id else Uuid.random()
    val continued = if (continueLast) history.last().id else null
    return ReplyPlan(
        history = history,
        replyNodeId = replyNodeId,
        continuedMessageId = continued,
        existingMessageIds = history.mapTo(HashSet()) { it.id },
        anchorNodeId = nodes.getOrNull(history.lastIndex)?.id,
    )
}

/** 按计划组装 agent 请求：只把计划里的历史交给 agent，计划里没有续写的消息时让 agent 新开一条 */
internal fun ReplyPlan.applyTo(request: AgentRequest): AgentRequest =
    request.copy(messages = history, forceNewMessage = continuedMessageId == null)

/** agent 发来本轮回复的最新内容：写进会话，并记下本轮正在写的消息 */
internal fun ConversationSession.applyReply(plan: ReplyPlan, message: UIMessage) {
    update { it.upsertMessage(plan.replyNodeId, message, insertAfterNodeId = plan.anchorNodeId) }
    replyMessageId.value = message.id
}

/**
 * 本轮产出的助手消息：本轮开始时还没有的（包括切片出来的新气泡），以及本轮续写的那条。
 * 本轮之前她说过的话不算，哪怕它是会话最后一条。
 */
internal fun ReplyPlan.repliesIn(finished: Conversation): List<UIMessage> =
    finished.currentMessages.filter {
        it.role == MessageRole.ASSISTANT && (it.id !in existingMessageIds || it.id == continuedMessageId)
    }

/** 生成结束后的通知预览：只取本轮产出的最后一条 */
internal fun ReplyPlan.previewIn(finished: Conversation): String? =
    repliesIn(finished).lastOrNull()
        ?.previewTextForWhale()
        ?.take(50)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

/** 闹钟轮有没有真的发出内容：本轮产出了界面上看得见的助手消息 */
internal fun ReplyPlan.sentIn(finished: Conversation): Boolean =
    repliesIn(finished).any { it.hasVisibleContent() }

/**
 * 一轮生成的「结束」只报一次（W-047）。
 *
 * runTurn 正常结束、出错、被取消时都要发 `ChatGenerationEnded`：通知看它，聊天页也靠它补显新消息。
 * 以前 finally 里发一次、catch 里又 tryEmit 一次，取消时会发两次（syncNewMessages 跑两遍），
 * 而且 tryEmit 在事件总线缓冲满时会把结束事件丢掉。现在先到的那次算数，并且在 NonCancellable 里挂起发送，不会丢。
 */
internal class TurnEndSignal(private val send: suspend (preview: String?) -> Unit) {
    private val sent = java.util.concurrent.atomic.AtomicBoolean(false)

    suspend fun ended(preview: String?) {
        if (!sent.compareAndSet(false, true)) return
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { send(preview) }
    }
}
