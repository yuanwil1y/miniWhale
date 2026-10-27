package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.domain.chat.ChatMessage

/**
 * 「一条一条发」的待发队列（任务书第三点五节）。
 *
 * 只做纯逻辑：顺序、更新、打断时一次性放完。真正的 `delay` 在 `ChatViewModel` 里，
 * 这样队列本身可以单测（顺序 / 打断 / 历史不重播）。
 */
class RevealQueue {

    private val pending = LinkedHashMap<Long, ChatMessage>()

    val size: Int get() = pending.size

    val isEmpty: Boolean get() = pending.isEmpty()

    fun isNotEmpty(): Boolean = pending.isNotEmpty()

    fun contains(id: Long): Boolean = pending.containsKey(id)

    fun enqueue(messages: List<ChatMessage>) {
        messages.forEach { pending[it.id] = it }
    }

    fun peek(): ChatMessage? = pending.values.firstOrNull()

    fun removeFirst(): ChatMessage? {
        val head = pending.values.firstOrNull() ?: return null
        pending.remove(head.id)
        return head
    }

    /** 同一条消息内容更新（真实模型会不断刷新同一条）：已在队列里就替换，不在就不管。 */
    fun update(message: ChatMessage) {
        if (pending.containsKey(message.id)) pending[message.id] = message
    }

    /** 这些消息不该再显示（被切片替换掉了），从队列里去掉。 */
    fun remove(ids: Collection<Long>) {
        ids.forEach { pending.remove(it) }
    }

    /** 打断：剩下的立即全部放出来（返回按顺序的列表并清空）。 */
    fun flush(): List<ChatMessage> {
        val all = pending.values.toList()
        pending.clear()
        return all
    }

    fun clear() = pending.clear()
}
