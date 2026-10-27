package whaleui.nightsky.ltd.whale

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import kotlin.uuid.Uuid

/**
 * 启动时补回复（W-021）。
 *
 * 待发批次只在内存里：他发完立刻划掉 App（不少 ROM 会直接杀进程），消息已经入库却没人回。
 * 进程再起来时，如果会话最后一条就是他的消息、她之后一句都没回过、而且是最近 [MAX_AGE_MS] 内发的，
 * 就把这条重新放进待发批次，照常等 7 秒再回（他接着打字也会合进同一批）。
 *
 * 只看「最近 30 分钟」：更早的消息多半是以前生成失败 / 被他自己停掉的，升级或隔天打开 App
 * 时突然冒出一条迟到很久的回复反而奇怪。
 */
object WhaleUnansweredReply {

    const val MAX_AGE_MS: Long = 30 * 60_000L

    /** 需要补回复的那条消息 id；不需要时返回 null。 */
    fun find(
        messages: List<UIMessage>,
        nowMillis: Long,
        maxAgeMs: Long = MAX_AGE_MS,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Uuid? {
        val last = messages.lastOrNull() ?: return null
        if (last.role != MessageRole.USER) return null
        val age = nowMillis - last.createdAt.toInstant(zone).toEpochMilliseconds()
        if (age < 0 || age > maxAgeMs) return null
        return last.id
    }
}
