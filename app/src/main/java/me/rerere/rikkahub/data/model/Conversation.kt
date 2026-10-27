package me.rerere.rikkahub.data.model

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.util.InstantSerializer
import me.rerere.rikkahub.data.datastore.DEFAULT_ASSISTANT_ID
import me.rerere.rikkahub.data.datastore.Settings
import java.time.Instant
import kotlin.uuid.Uuid

@Serializable
data class Conversation(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val title: String = "",
    val messageNodes: List<MessageNode>,
    val chatSuggestions: List<String> = emptyList(),
    val isPinned: Boolean = false,
    @Serializable(with = InstantSerializer::class)
    val createAt: Instant = Instant.now(),
    @Serializable(with = InstantSerializer::class)
    val updateAt: Instant = Instant.now(),
    val customSystemPrompt: String? = null,
    val modeInjectionIds: Set<Uuid> = emptySet(),
    val lorebookIds: Set<Uuid> = emptySet(),
    // 会话开始后固定的配置，null 表示尚未开始、跟随助手
    val config: ConversationConfig? = null,
    // 工作区当前目录。工作区功能已在 trim S6 删除；字段保留，对应 conversationentity.workspace_cwd 列，只做原样读写
    val workspaceCwd: String? = null,
    // 所属文件夹（助手内分组），null 表示未归入任何文件夹
    val folderId: Uuid? = null,
    @Transient
    val newConversation: Boolean = false
) {
    val files: List<Uri>
        get() = messageNodes
            .flatMap { node -> node.messages.flatMap { it.parts } }
            .localFileUrls()
            .map { it.toUri() }

    /**
     *  当前选中的 message
     */
    val currentMessages
        get(): List<UIMessage> {
            return messageNodes.map { node -> node.messages[node.selectIndex] }
        }

    fun updateCurrentMessages(messages: List<UIMessage>): Conversation {
        val newNodes = this.messageNodes.toMutableList()

        messages.forEachIndexed { index, message ->
            val node = newNodes
                .getOrElse(index) { message.toMessageNode() }

            val newMessages = node.messages.toMutableList()
            var newMessageIndex = node.selectIndex
            if (newMessages.any { it.id == message.id }) {
                newMessages[newMessages.indexOfFirst { it.id == message.id }] = message
            } else {
                newMessages.add(message)
                newMessageIndex = newMessages.lastIndex
            }

            val newNode = node.copy(
                messages = newMessages,
                selectIndex = newMessageIndex
            )

            // 更新newNodes
            if (index > newNodes.lastIndex) {
                newNodes.add(newNode)
            } else {
                newNodes[index] = newNode
            }
        }

        return this.copy(
            messageNodes = newNodes
        )
    }

    /**
     * 写入一条消息：[nodeId] 节点里已有同 id 的消息就原地替换（不改变选中的分支），否则作为新分支加入并选中；
     * 节点不存在时插在 [insertAfterNodeId] 后面（找不到它或为 null 时追加到末尾）。按节点 id 而不是下标定位，
     * 生成期间别的节点被增删、分支被切换，回复也不会写错位置。
     */
    fun upsertMessage(nodeId: Uuid, message: UIMessage, insertAfterNodeId: Uuid? = null): Conversation {
        // ---- 小鲸鱼补丁（round4，根治重复快照）----
        // 先按「消息 id」定位：同一条消息已经在某个节点里，就更新那个节点。
        // 为什么必须这样：agent 会在某些轮次**续写一条已存在的 assistant 消息**（例如工具回环、审批之后）。
        // 这时传进来的 nodeId 是新节点 id，
        // 而消息本身还挂在旧节点里 —— 只按 nodeId 找会找不到 → 追加一个新节点 →
        // 同一个 UIMessage.id 同时出现在两个节点（本轮实测到 3 份快照）。
        val byMessage = messageNodes.indexOfLast { node -> node.messages.any { it.id == message.id } }
        if (byMessage != -1) {
            val node = messageNodes[byMessage]
            val existing = node.messages.indexOfFirst { it.id == message.id }
            val updated = node.copy(
                messages = node.messages.toMutableList().also { it[existing] = message },
                selectIndex = node.selectIndex,
            )
            return copy(messageNodes = messageNodes.toMutableList().also { it[byMessage] = updated })
        }

        // 生成中的回复几乎总在最后一个节点
        val index = messageNodes.indexOfLast { it.id == nodeId }
        if (index == -1) {
            val node = MessageNode(id = nodeId, messages = listOf(message))
            val anchor = insertAfterNodeId?.let { id -> messageNodes.indexOfFirst { it.id == id } } ?: -1
            if (anchor == -1) return copy(messageNodes = messageNodes + node)
            return copy(messageNodes = messageNodes.toMutableList().also { it.add(anchor + 1, node) })
        }
        val node = messageNodes[index]
        val existing = node.messages.indexOfFirst { it.id == message.id }
        val updated = if (existing == -1) {
            node.copy(messages = node.messages + message, selectIndex = node.messages.size)
        } else {
            node.copy(messages = node.messages.toMutableList().also { it[existing] = message })
        }
        return copy(messageNodes = messageNodes.toMutableList().also { it[index] = updated })
    }

    companion object {
        fun ofId(
            id: Uuid,
            assistantId: Uuid = DEFAULT_ASSISTANT_ID,
            messages: List<MessageNode> = emptyList(),
            newConversation: Boolean = false
        ) = Conversation(
            id = id,
            assistantId = assistantId,
            messageNodes = messages,
            newConversation = newConversation,
        )
    }
}

@Serializable
data class MessageNode(
    val id: Uuid = Uuid.random(),
    val messages: List<UIMessage>,
    val selectIndex: Int = 0,
    @Transient
    val isFavorite: Boolean = false,
) {
    val currentMessage get() = if (messages.isEmpty() || selectIndex !in messages.indices) {
        throw IllegalStateException(
            "MessageNode has no valid current message: messages.size=${messages.size}, selectIndex=$selectIndex"
        )
    } else {
        messages[selectIndex]
    }

    val role get() = messages.firstOrNull()?.role ?: MessageRole.USER

    companion object {
        fun of(message: UIMessage) = MessageNode(
            messages = listOf(message),
            selectIndex = 0
        )
    }
}

/**
 * 去掉还有未执行、又没法继续执行的工具调用的消息（比如还在等待审批），这样的消息发给模型会被拒绝。
 * 已经批准、拒绝或回答过的工具调用之后会继续执行，保留。只处理下标小于 [untilNode] 的节点。
 */
internal fun Conversation.withoutUnresumableToolCalls(untilNode: Int = messageNodes.size): Conversation {
    val nodes = messageNodes.mapIndexedNotNull { index, node ->
        if (index >= untilNode) return@mapIndexedNotNull node
        val unexecuted = node.currentMessage.getTools().filter { !it.isExecuted }
        if (unexecuted.isEmpty() || unexecuted.any { it.canResumeExecution }) return@mapIndexedNotNull node
        val messages = node.messages.filter { it.id != node.currentMessage.id }
        if (messages.isEmpty()) return@mapIndexedNotNull null
        node.copy(messages = messages, selectIndex = (node.selectIndex - 1).coerceIn(messages.indices))
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

fun UIMessage.toMessageNode(): MessageNode {
    return MessageNode(
        messages = listOf(this),
        selectIndex = 0
    )
}

/**
 * 给还没有模型快照的消息补上快照，没有可补的消息时原样返回。
 *
 * 在保存会话时调用：新生成的消息由此带上快照，引入快照之前的旧消息也在会话下次保存时补齐。
 * 模型已经被删除的旧消息无从补起，保持原样。
 */
fun Conversation.fillModelSnapshots(settings: Settings): Conversation {
    val models = settings.providers.flatMap { it.models }.associateBy { it.id }
    val nodes = messageNodes.map { node ->
        val messages = node.messages.map { it.fillModelSnapshot(models) }
        if (messages == node.messages) node else node.copy(messages = messages)
    }
    return if (nodes == messageNodes) this else copy(messageNodes = nodes)
}

private fun UIMessage.fillModelSnapshot(models: Map<Uuid, Model>): UIMessage {
    if (modelSnapshot != null) return this
    val model = modelId?.let(models::get) ?: return this
    return copy(modelSnapshot = ModelSnapshot(modelId = model.modelId, displayName = model.displayName))
}

/** 本地附件引用，包含工具结果中的嵌套附件。 */
internal fun List<UIMessagePart>.localFileUrls(): Set<String> = buildSet {
    this@localFileUrls.forEach { part ->
        val url = when (part) {
            is UIMessagePart.Image -> part.url
            is UIMessagePart.Document -> part.url
            is UIMessagePart.Video -> part.url
            is UIMessagePart.Audio -> part.url
            is UIMessagePart.Tool -> {
                addAll(part.output.localFileUrls())
                null
            }

            else -> null
        }
        if (url?.startsWith("file://") == true) add(url)
    }
}

/** 替换本地附件的地址，覆盖范围与 [localFileUrls] 一致，包含工具结果中的嵌套附件。 */
internal fun UIMessagePart.mapLocalFileUrls(transform: (String) -> String): UIMessagePart {
    fun String.mapped() = if (startsWith("file://")) transform(this) else this
    return when (this) {
        is UIMessagePart.Image -> copy(url = url.mapped())
        is UIMessagePart.Document -> copy(url = url.mapped())
        is UIMessagePart.Video -> copy(url = url.mapped())
        is UIMessagePart.Audio -> copy(url = url.mapped())
        is UIMessagePart.Tool -> copy(output = output.map { it.mapLocalFileUrls(transform) })
        else -> this
    }
}
