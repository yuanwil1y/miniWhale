package me.rerere.rikkahub.data.model

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.finishPendingTools
import kotlin.time.Clock
import kotlin.uuid.Uuid

/**
 * 删除一条消息：节点里没有别的分支时整个节点一起删掉，否则选中的分支向前收拢。
 * 消息不存在时返回 null。
 */
internal fun Conversation.deleteMessage(messageId: Uuid): Conversation? {
    val targetNodeIndex = messageNodes.indexOfFirst { node -> node.messages.any { it.id == messageId } }
    if (targetNodeIndex == -1) return null

    val updatedNodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index != targetNodeIndex) return@mapIndexedNotNull node
        val nextMessages = node.messages.filterNot { it.id == messageId }
        if (nextMessages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = nextMessages, selectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex))
    }
    return copy(messageNodes = updatedNodes)
}

/**
 * 和 [deleteMessage] 一样，但删掉**所有节点里**的这条消息。
 *
 * 界面消息 id 是 `UIMessage.id` 的确定性哈希（`MessageIds.forUuid`），同一个id 可能出现在多个节点里；
 * 只删第一个命中节点会留下其余副本，界面看起来"删了、重启后又回来"。
 * 没有命中任何节点时返回 null（调用方可据此判断没改）。
 */
internal fun Conversation.deleteMessageEverywhere(messageId: Uuid): Conversation? {
    var changed = false
    val updatedNodes = messageNodes.mapNotNull { node ->
        if (node.messages.none { it.id == messageId }) return@mapNotNull node
        changed = true
        val nextMessages = node.messages.filterNot { it.id == messageId }
        if (nextMessages.isEmpty()) return@mapNotNull null
        node.copy(messages = nextMessages, selectIndex = node.selectIndex.coerceAtMost(nextMessages.lastIndex))
    }
    return if (changed) copy(messageNodes = updatedNodes) else null
}

/**
 * 原地替换这条消息在**所有节点**里的副本的内容（不建新分支、id 不变）。
 * 小鲸鱼多图消息删掉其中一张图时用（F5）：界面上每张图一个气泡，库里是同一条消息。没命中时返回 null。
 */
internal fun Conversation.replaceMessagePartsEverywhere(messageId: Uuid, parts: List<UIMessagePart>): Conversation? {
    var changed = false
    val updatedNodes = messageNodes.map { node ->
        if (node.messages.none { it.id == messageId }) return@map node
        changed = true
        node.copy(messages = node.messages.map { if (it.id == messageId) it.copy(parts = parts) else it })
    }
    return if (changed) copy(messageNodes = updatedNodes) else null
}

/**
 * 生成被停止时，最后一条消息里还没执行的工具补上取消的结果，否则这条消息之后没法继续发给模型。
 * 没有需要补的工具时返回 null。
 */
internal fun Conversation.cancelInterruptedTools(): Conversation? {
    val lastNode = messageNodes.lastOrNull() ?: return null
    val updatedMessage = lastNode.currentMessage.finishPendingTools(::cancelToolByUser)
    if (updatedMessage === lastNode.currentMessage) return null
    return upsertMessage(lastNode.id, updatedMessage)
}

private fun cancelToolByUser(tool: UIMessagePart.Tool): UIMessagePart.Tool = tool.copy(
    output = listOf(
        UIMessagePart.Text(
            """{"status":"cancelled","error":"Generation cancelled by user before tool execution completed."}"""
        )
    ),
    // 执行到一半被停止的工具，耗时算到取消为止
    finishedAt = tool.finishedAt ?: tool.startedAt?.let { Clock.System.now() },
)
