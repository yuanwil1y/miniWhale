package whaleui.nightsky.ltd.reply

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage

/**
 * 相邻 assistant 消息的合并（只用于组装请求，不改数据库）。
 *
 * 聊天页把一个角色回复拆成了多个气泡（见 [WhaleMessageSlicer]），但发给模型时
 * 必须还原成「一条 assistant 消息」，否则 DeepSeek 看到的上下文与未拆分时不一样：
 * 思考模式带工具时缺少 `reasoning_content` 会直接 400。
 *
 * 规则：
 * - 只合并**连续出现**的 assistant 消息，中间夹了 user/system/tool 就断开；
 * - 相邻 Text 部件用 `\n` 连接成一个 Text，其余部件按原顺序追加；
 * - 合并结果保留这一组第一条消息的 id/时间等字段；
 * - 非 assistant 消息原样保留（同一个对象，不复制）。
 *
 * 调用方（`ChatStepHooks`）只拿本轮开始前的历史来合并；本轮正在写的助手消息另外接在最后，
 * 不会被并进她的上一条（W-026）。
 *
 * 不用 RikkaHub 原生的 `appendMessage`：它拼接相邻文本时不加换行，会把两行粘成一行。
 */
object WhaleMergeSlices {

    fun mergeAdjacentAssistant(messages: List<UIMessage>): List<UIMessage> {
        if (messages.isEmpty()) return messages

        val result = ArrayList<UIMessage>(messages.size)
        val group = mutableListOf<UIMessage>()

        fun flushGroup() {
            if (group.isEmpty()) return
            if (group.size == 1) {
                result.add(group[0])
            } else {
                val mergedParts = WhaleMessageSlicer.mergeAdjacentTextParts(group.flatMap { it.parts })
                result.add(group.first().copy(parts = mergedParts))
            }
            group.clear()
        }

        for (message in messages) {
            if (message.role == MessageRole.ASSISTANT) {
                group.add(message)
            } else {
                flushGroup()
                result.add(message)
            }
        }
        flushGroup()

        return result
    }
}
