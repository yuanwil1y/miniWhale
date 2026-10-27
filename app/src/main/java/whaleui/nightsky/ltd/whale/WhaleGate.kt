package whaleui.nightsky.ltd.whale

import kotlin.uuid.Uuid

/**
 * 小鲸鱼在 RikkaHub 侧的「身份闸门」。
 *
 * RikkaHub 的代码（`ConversationSession`、`ChatRequestFactory`、`ChatToolFactory`）在 `me.rerere.rikkahub`
 * 包里，不能反过来依赖 `whaleui.nightsky.ltd` 的具体实现。所以原生侧只读这里几个进程级标记，
 * 由我们自己的代码（`WhaleBootstrap` / `RikkaChatRepository`）在启动时写入。
 *
 * 两个标记都是「初始化后只写一次」，读的时候加 @Volatile 够了。
 */
object WhaleGate {

    /** 小鲸鱼 Assistant 的 id。阶段 2 的 `WhaleBootstrap` 首次启动时创建并写入。 */
    @Volatile
    var assistantId: Uuid? = null

    /** 我们的固定对话 id。`RikkaChatRepository` 第一次解析/创建对话时写入。 */
    @Volatile
    var conversationId: Uuid? = null

    /**
     * 进程启动时**同步**从持久化的值恢复两个标记（W-009）。
     *
     * 闹钟把进程冷启动时，`WhaleBootstrap.ensure` 还在异步写设置，第一轮请求可能先读到 null，
     * 按「没有人设」组装。Application.onCreate 里先用上一次记下的 id 填上；
     * 已经有值（bootstrap 写过）时不覆盖。
     */
    fun restore(assistantId: Uuid?, conversationId: Uuid?) {
        if (this.assistantId == null && assistantId != null) this.assistantId = assistantId
        if (this.conversationId == null && conversationId != null) this.conversationId = conversationId
    }

    /** 这个 Assistant 是不是小鲸鱼。未初始化时一律 false，原生行为完全不变。 */
    fun isWhaleAssistant(id: Uuid?): Boolean {
        val whale = assistantId ?: return false
        return id == whale
    }

    /** 这个会话是不是小鲸鱼的那个固定对话（或绑在小鲸鱼助手上）。 */
    fun isWhaleConversation(conversationId: Uuid?, assistantId: Uuid?): Boolean {
        val fixed = this.conversationId
        if (fixed != null && conversationId == fixed) return true
        val whale = this.assistantId
        return whale != null && assistantId == whale
    }
}
