package whaleui.nightsky.ltd.whale

import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode

/**
 * 会话节点的**数据层归一化**（根治「一个气泡里混着两条消息」和 LazyColumn key 冲突）。
 *
 * 上一轮实测到的坏数据：三个节点（node id 各不相同）里装着**同一个 `UIMessage.id`**，
 * 内容是同一个回复在生成过程中的三次快照（`length(messages)` 640 → 983 → 1588）。
 * 这种数据一旦进到界面，就会出现：
 * - 三条界面消息拿到同一个 id → LazyColumn `Key "msg-…" was already used` 崩溃；
 * - 只做界面去重时，`Updated` 会把前一条的内容覆盖成后一条 → 「一个气泡里既有工具结果又有后来的消息」。
 *
 * 这里的处理是**在数据层把它们合成一条**：同一个消息 id 只保留内容最完整的那份（放在它最后一次出现的位置），
 * 其余丢掉。这样界面层不需要靠去重来掩盖问题（去重仍然保留作兜底）。
 *
 * 纯函数，不读写数据库，方便单测。
 */
object ConversationNormalizer {

    fun dedupeByMessageId(conversation: Conversation): Conversation {
        val nodes = conversation.messageNodes
        if (nodes.size < 2) return conversation

        // 每个消息 id 出现的位置；只有一个位置的直接放过
        val occurrences = LinkedHashMap<String, MutableList<Int>>()
        nodes.forEachIndexed { index, node ->
            node.primaryMessageId()?.let { id ->
                occurrences.getOrPut(id) { mutableListOf() }.add(index)
            }
        }
        val duplicated = occurrences.filterValues { it.size > 1 }
        if (duplicated.isEmpty()) return conversation
        return conversation.copy(messageNodes = dedupeNodes(nodes, duplicated))
    }

    private fun dedupeNodes(nodes: List<MessageNode>, duplicated: Map<String, List<Int>>): List<MessageNode> {
        // 兜底触发要留痕（只打节点数和消息 id，不打正文）：源头修好后这里应该永远是 0 次。
        if (me.rerere.rikkahub.BuildConfig.DEBUG) {
            runCatching {
                android.util.Log.w(
                    "WhaleIntegrity",
                    "duplicate message ids in ${nodes.size} nodes: " +
                        duplicated.entries.joinToString(" ") { (id, positions) ->
                            "${id.take(8)}@${positions.size}"
                        },
                )
            }
        }

        return collapse(nodes, duplicated.values)
    }

    /**
     * 只合并 [messageId] 这一条的重复（生成结束时用，W-058）：只比较 id，不拷贝整段历史；没有重复时原样返回。
     */
    fun dedupeMessage(conversation: Conversation, messageId: kotlin.uuid.Uuid): Conversation {
        val nodes = conversation.messageNodes
        var positions: MutableList<Int>? = null
        nodes.forEachIndexed { index, node ->
            if (runCatching { node.currentMessage.id }.getOrNull() == messageId) {
                (positions ?: mutableListOf<Int>().also { positions = it }).add(index)
            }
        }
        val found = positions ?: return conversation
        if (found.size < 2) return conversation
        return conversation.copy(messageNodes = collapse(nodes, listOf(found)))
    }

    /** 每个重复 id：保留内容最丰富的那一条，挪到它最后一次出现的位置，其余位置丢掉 */
    private fun collapse(nodes: List<MessageNode>, groups: Collection<List<Int>>): List<MessageNode> {
        val drop = HashSet<Int>()
        val replace = HashMap<Int, MessageNode>()
        groups.forEach { positions ->
            val keepAt = positions.maxBy { rank(nodes[it]) }
            val keepNode = nodes[keepAt]
            val lastPos = positions.last()
            if (keepAt != lastPos) {
                drop += keepAt
                replace[lastPos] = keepNode
            }
            positions.filter { it != keepAt && it != lastPos }.forEach { drop += it }
        }

        val result = ArrayList<MessageNode>(nodes.size)
        nodes.forEachIndexed { index, node ->
            if (index in drop) return@forEachIndexed
            result += replace[index] ?: node
        }
        return result
    }

    /** 内容量：文本长度 + 部件数 + 消息条数（多步生成的最后一条通常最全）。 */
    private fun rank(node: MessageNode): Int {
        val message = node.currentMessage
        val text = message.parts.sumOf { part ->
            when (part) {
                is me.rerere.ai.ui.UIMessagePart.Text -> part.text.length
                else -> 1
            }
        }
        return text + message.parts.size + node.messages.size
    }

    /**
     * 用来判重的消息 id。一个节点可能有多条消息（分支），只有「当前选中的那条」才是界面上的那一条，
     * 所以按 `currentMessage` 判重；节点里没有消息时返回 null（不参与判重，也不会被删）。
     */
    private fun MessageNode.primaryMessageId(): String? =
        runCatching { currentMessage }.getOrNull()?.id?.toString()

    /** 方便日志/断言：某条消息在会话里出现了几次。 */
    fun countMessage(nodes: List<MessageNode>, messageId: String): Int =
        nodes.count { it.primaryMessageId() == messageId }
}
