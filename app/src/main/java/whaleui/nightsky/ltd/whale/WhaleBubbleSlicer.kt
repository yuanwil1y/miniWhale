package whaleui.nightsky.ltd.whale

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.finishReasoning
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import whaleui.nightsky.ltd.reply.WhaleMessageSlicer
import kotlin.uuid.Uuid

/**
 * 把「一条 assistant 消息」按部件顺序切片成多个 MessageNode，也就是界面上的多个气泡。
 *
 * 接入点：`ConversationSession.finishGeneration()`（原生扩展点，任务书 1.5）。
 * 切成 N 个节点后，界面天然就是 N 个气泡，UI 一行都不用改；
 * 请求侧再由 `WhaleMergeSlices` 合并回一条，模型看到的和没拆过一模一样。
 *
 * 幂等：一条消息如果已经是单行/单切片，返回原会话，不做任何改动。
 *
 * 静默：本轮回复在界面上什么都不显示时（[SILENT] 清空了文字、只剩工具调用或思考），整个节点删掉（W-048）。
 */
object WhaleBubbleSlicer {

    /**
     * 生成结束时整理本轮回复所在的那**一个**节点：收尾思考、去重、静默删除、切片。
     *
     * 只碰本轮回复（W-058）：以前每轮都对整段历史做 `dedupeByMessageId` 和 `finishReasoning`，
     * 在主线程上每轮 O(节点数) 地拷贝整段历史；更早的节点早就收过尾了。历史里遗留的坏数据
     * 改在加载会话时（后台线程）整体去重一次（`ChatService.loadConversation`）。
     *
     * @param replyMessageId 本轮正在写的消息；为 null 表示本轮没有任何输出，什么都不做
     * @param isVisible 这条消息在界面上会不会出气泡（`hasVisibleContent`，由调用方传入，避免 whale 包反过来依赖 data 包）
     */
    fun splitReplyNodes(
        original: Conversation,
        replyMessageId: Uuid?,
        isVisible: (UIMessage) -> Boolean,
    ): Conversation {
        if (replyMessageId == null) return original
        val whale = WhaleGate.isWhaleConversation(original.id, original.assistantId)

        // 同一个消息 id 出现在多个节点（上一轮 640/983/1588 那种快照重复）时合并成一条，
        // 否则界面会拿到重复的 id：轻则两个气泡的内容串在一起，重则 LazyColumn key 冲突崩溃。只查本轮这条。
        val conversation = if (whale) ConversationNormalizer.dedupeMessage(original, replyMessageId) else original

        val nodes = conversation.messageNodes
        val index = nodes.indexOfFirst { node -> node.messages.any { it.id == replyMessageId } }
        if (index < 0) return conversation

        // 被取消 / 出错时还没收尾的思考补上结束时间（只有本轮回复可能没收尾）
        val node = nodes[index].let { it.copy(messages = it.messages.map { message -> message.finishReasoning() }) }
        fun withReplyNodes(replacement: List<MessageNode>) =
            conversation.copy(messageNodes = nodes.take(index) + replacement + nodes.drop(index + 1))

        if (!whale) return withReplyNodes(listOf(node))
        val message = node.currentMessage
        if (message.role != MessageRole.ASSISTANT) return withReplyNodes(listOf(node))

        // 静默轮：清理把这一轮的文字清空了 —— 整条节点删掉，不落库、不留痕（任务书 1.5 / 6.2）。
        // 先调工具再 [SILENT] 时，消息里还剩工具调用（带着工具结果）：界面不显示，但会进后续历史，
        // 所以按「有没有可见内容」判断，连同工具调用一起删（W-048）。工具调用和结果在同一个部件里，
        // 整条删掉不会留下不成对的 tool_call / tool。
        if (!isVisible(message)) return withReplyNodes(emptyList())

        val slices = WhaleMessageSlicer.sliceParts(message.parts)
        if (slices.size <= 1) return withReplyNodes(listOf(node))

        return withReplyNodes(
            slices.map { parts ->
                node.copy(
                    id = Uuid.random(),
                    messages = listOf(message.copy(id = Uuid.random(), parts = parts)),
                    selectIndex = 0,
                )
            }
        )
    }
}
