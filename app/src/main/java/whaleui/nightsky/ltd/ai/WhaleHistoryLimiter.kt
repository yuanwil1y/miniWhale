package whaleui.nightsky.ltd.ai

import android.util.Log
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import me.rerere.rikkahub.BuildConfig
import whaleui.nightsky.ltd.whale.WhaleGate

/**
 * 只带最近一段历史（决定 5 / 任务书 4.2）。
 *
 * - 按「轮」截断：一轮 = 一条 user 消息 + 它后面所有非 user 消息（她的多条气泡仍算同一轮）；
 * - **按块滑动，保证前缀缓存命中**：不超过 [maxTurns] 轮就不动；超过时一次截到 [keepTurns] 轮。
 *   这样请求的前缀在大约 `maxTurns - keepTurns` 轮内保持不变，DeepSeek 的前缀缓存能持续命中；
 * - 截断后第一条（system 之后）一定是 user 消息；
 * - 工具调用和工具结果都装在 assistant 消息里，按轮切不会把它们拆开；
 * - 原生的 `contextMessageLimit` 和上下文压缩都不用（它们由设置关掉了）。
 *
 * 只在小鲸鱼会话里生效。轮数可配（[WhaleHistoryConfig]），默认 40/50。
 */
class WhaleHistoryLimiter(
    /** 测试里可钉死；生产为 null → **每次调用时**读 [WhaleHistoryConfig]（这样调试改阈值立刻生效）。 */
    private val keepTurnsOverride: Int? = null,
    private val maxTurnsOverride: Int? = null,
) : InputMessageTransformer {

    private val keepTurns: Int get() = keepTurnsOverride ?: WhaleHistoryConfig.keepTurns
    private val maxTurns: Int get() = maxTurnsOverride ?: WhaleHistoryConfig.maxTurns

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> {
        if (!WhaleGate.isWhaleAssistant(ctx.assistant.id)) return messages
        return limit(messages, keepTurns, maxTurns)
    }

    /** 纯函数部分（方便单测，不需要 Android 的 TransformerContext）。 */
    internal fun limit(
        messages: List<UIMessage>,
        keepTurns: Int = this.keepTurns,
        maxTurns: Int = this.maxTurns,
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages

        // 开头的非 user 消息（system 等）永远保留
        val firstUserIndex = messages.indexOfFirst { it.role == MessageRole.USER }
        if (firstUserIndex < 0) return messages
        val head = messages.subList(0, firstUserIndex)

        // 每一轮的起点：每组**连续 user 消息**的第一条（round5 3.7：连发的几条算同一轮）
        val turnStarts = messages.indices.filter { index ->
            messages[index].role == MessageRole.USER &&
                (index == 0 || messages[index - 1].role != MessageRole.USER)
        }
        val totalTurns = turnStarts.size
        if (totalTurns <= maxTurns) return messages

        val keepFromTurn = totalTurns - keepTurns
        val cutIndex = turnStarts[keepFromTurn]
        val tail = messages.subList(cutIndex, messages.size)
        // 兜底：切完第一条必须是 user
        val firstKept = tail.firstOrNull { it.role == MessageRole.USER } ?: return messages
        val result = if (tail.firstOrNull() === firstKept) tail else tail.dropWhile { it !== firstKept }

        if (BuildConfig.DEBUG) {
            runCatching {
                Log.i("WhaleHistory", historyLogLine(keepTurns, keepFromTurn + 1, totalTurns, messages.size, head.size + result.size))
            }
        }
        return head + result
    }
}

/** 历史轮数配置（默认 40/50，可按决定 5 调整）。 */
object WhaleHistoryConfig {
    /** 保留多少轮 */
    @Volatile
    var keepTurns: Int = 40

    /** 超过多少轮开始截断 */
    @Volatile
    var maxTurns: Int = 50

    /** 测试用：恢复默认。 */
    fun reset() {
        keepTurns = 40
        maxTurns = 50
    }
}

/**
 * 历史截断的日志行（纯函数，方便单测）：只打「带了几轮、从第几轮开始、消息数变化」，
 * **不打消息正文、不打 Key**。
 */
internal fun historyLogLine(
    keptTurns: Int,
    fromTurn: Int,
    totalTurns: Int,
    beforeMessages: Int,
    afterMessages: Int,
): String = "history: kept $keptTurns turns (from turn $fromTurn of $totalTurns), " +
    "messages $beforeMessages -> $afterMessages"