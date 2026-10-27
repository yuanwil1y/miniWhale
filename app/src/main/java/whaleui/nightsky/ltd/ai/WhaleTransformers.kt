package whaleui.nightsky.ltd.ai

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.transformers.InputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.OutputMessageTransformer
import me.rerere.rikkahub.data.ai.transformers.TransformerContext
import whaleui.nightsky.ltd.reply.WhaleMessageSlicer
import whaleui.nightsky.ltd.reply.WhaleReplyPipeline
import whaleui.nightsky.ltd.sticker.DELETED_STICKER_LINE
import whaleui.nightsky.ltd.sticker.herStickerLine
import whaleui.nightsky.ltd.sticker.hisStickerLine
import whaleui.nightsky.ltd.sticker.isStickerPart
import whaleui.nightsky.ltd.sticker.stickerDetailOrNull
import whaleui.nightsky.ltd.sticker.stickerDescriptionOrNull
import whaleui.nightsky.ltd.sticker.stickerIdOrNull
import whaleui.nightsky.ltd.sticker.stickerUrlAlive
import whaleui.nightsky.ltd.whale.WhaleGate
import kotlin.uuid.Uuid

/**
 * 小鲸鱼专用的两个原生扩展点实现（任务书 1.5 / 1.6）。
 *
 * 都是「只改发给模型 / 只改显示」的一侧，**不落库**：数据库里存的是原始消息结构。
 * 通过 `ChatRequestFactory` 的 inputTransformers / outputTransformers 列表接入。
 *
 * 真正的转换逻辑抽成了 internal 顶层函数（[compatTransform] / [finishReply] / [previewReply]），
 * 这样单元测试不用构造 Android 的 `Context` 就能覆盖每一处映射。
 */

/**
 * DeepSeek 兼容性修补（`InputMessageTransformer`，放在原生列表**最后**）。
 *
 * 要绕开的硬约束：
 * 1. DeepSeek 规定 **system / assistant 消息里出现图片会直接 400**，只能出现在 user 消息里；
 * 2. 原生请求构造会**忽略 Audio 部件**，只有一条语音、没有文字的 assistant 消息会变成空 content；
 * 3. 原生 `toToolResultContent` 在模型勾了 IMAGE 时会把工具输出里的图片当真 image_url 发出去。
 *
 * 所以：他发来的图片（user 消息）原样保留；她自己的图片、她发出的语音、工具输出里的图片/语音，
 * 全部换成一句中文占位，让她"记得自己做过什么"，同时不会让请求 400。
 *
 * **表情（round5）**：双向都不带图，换成固定格式的一句文字（任务书 3.3）：
 * 她的 → `(你发了一个表情:<description>,编号 <id>)`，
 * 对方的 → `(对方发来一个表情:<detail>,编号 <id>)`；
 * 表情已经被删掉（文件不在了）→ `(一个已删除的表情)`。
 *
 * **连发合并（round5 3.7）**：转换完再做一次「连续 user 消息合成一条」，
 * 规则纯确定性（不依赖时间），历史里的连发也是同一个结果，前缀缓存不受影响。
 */
class WhaleCompatTransformer(
    private val isWhaleAssistant: (Uuid?) -> Boolean = WhaleGate::isWhaleAssistant,
) : InputMessageTransformer {

    override suspend fun transform(ctx: TransformerContext, messages: List<UIMessage>): List<UIMessage> =
        if (!isWhaleAssistant(ctx.assistant.id)) messages else compatTransform(messages)
}

/**
 * 回复清理（`OutputMessageTransformer`，任务书 1.5 第 5 步「清理」）。
 *
 * 正则与处理顺序逐字照抄 Hermes `persona_guard/__init__.py`，封装在 [WhaleReplyPipeline] 里。
 * - [onGenerationFinish]：生成结束后做最终清理；静默轮把本轮 parts 清空，交给
 *   `WhaleBubbleSlicer` 在 `finishGeneration` 里把整个节点删掉（不落库）。
 * - [visualTransform]：流式期间做同样的清理，并且隐藏"还没写完、以括号/方括号开头"的最后一行。
 */
class WhaleOutputTransformer(
    private val isWhaleAssistant: (Uuid?) -> Boolean = WhaleGate::isWhaleAssistant,
) : OutputMessageTransformer {

    override suspend fun onGenerationFinish(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { message ->
        if (message.role != MessageRole.ASSISTANT || !isWhaleAssistant(ctx.assistant.id)) {
            message
        } else {
            finishReply(message)
        }
    }

    override suspend fun visualTransform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> = messages.map { message ->
        if (message.role != MessageRole.ASSISTANT || !isWhaleAssistant(ctx.assistant.id)) {
            message
        } else {
            previewReply(message)
        }
    }
}

// ------------------------------------------------------------ 纯函数（可单测）

/** 把不在 user 消息里的图片 / 语音换成文字占位；表情双向都换成文字；最后合并连发的 user 消息。 */
internal fun compatTransform(messages: List<UIMessage>): List<UIMessage> {
    val converted = messages.map { message ->
        val isUser = message.role == MessageRole.USER
        message.copy(parts = message.parts.flatMap { it.toRequestParts(isUser = isUser) })
    }
    return mergeConsecutiveUserMessages(converted)
}

/**
 * 单个部件 → 发请求时真正用到的部件。
 *
 * @param isUser 当前消息是不是 user 消息（只有 user 消息允许保留图片）
 * @param inToolOutput 这个部件在工具输出里（`draw` / `text_to_speech` 的结果），
 *   图片的占位文案和顶层不同（「图已经发给对方了」而不是「你画了一张图」）
 */
private fun UIMessagePart.toRequestParts(
    isUser: Boolean,
    inToolOutput: Boolean = false,
): List<UIMessagePart> = when {
    // 表情优先判断：它的本质是 Image，但不能当图片发
    isStickerPart() -> listOf(UIMessagePart.Text(stickerRequestLine(this, isUser)))

    this is UIMessagePart.Tool ->
        listOf(
            copy(
                output = output.flatMap {
                    it.toRequestParts(isUser = false, inToolOutput = true)
                },
            )
        )

    isUser -> listOf(this)

    inToolOutput && this is UIMessagePart.Image ->
        listOf(UIMessagePart.Text("(图已经发给对方了)"))

    this is UIMessagePart.Image ->
        listOf(UIMessagePart.Text("(你画了一张图:${metadata?.get("prompt").asText()})"))

    this is UIMessagePart.Audio ->
        listOf(UIMessagePart.Text("(你发了一条语音:${metadata?.get("text").asText()})"))

    else -> listOf(this)
}

/** 表情在请求里的那一句（格式固定，任务书 3.3）。 */
internal fun stickerRequestLine(part: UIMessagePart, isUser: Boolean): String {
    val url = (part as? UIMessagePart.Image)?.url.orEmpty()
    if (!stickerUrlAlive(url)) return DELETED_STICKER_LINE
    val id = part.stickerIdOrNull().orEmpty()
    return if (isUser) {
        hisStickerLine(part.stickerDetailOrNull().orEmpty(), id)
    } else {
        herStickerLine(part.stickerDescriptionOrNull().orEmpty(), id)
    }
}

/**
 * 连续的 user 消息合成一条（任务书 3.7 第 1 条）。
 *
 * - `parts` 按原顺序保留：文字之间用 `\n` 连接，图片部件原样留在原位；
 * - 合成后的消息沿用**最后一条**的 id 和 createdAt（就是「当前这条」，报时按它算）；
 * - 只合并相邻的 user 消息，system / assistant / tool 消息不动；
 * - 规则确定、不依赖时间，所以历史里的连发每次合并结果都一样。
 */
internal fun mergeConsecutiveUserMessages(messages: List<UIMessage>): List<UIMessage> {
    if (messages.size < 2) return messages
    val result = ArrayList<UIMessage>(messages.size)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        if (message.role != MessageRole.USER) {
            result += message
            index++
            continue
        }
        var end = index + 1
        while (end < messages.size && messages[end].role == MessageRole.USER) end++
        if (end == index + 1) {
            result += message
        } else {
            val run = messages.subList(index, end)
            val merged = WhaleMessageSlicer.mergeAdjacentTextParts(run.flatMap { it.parts })
            result += run.last().copy(parts = merged)
        }
        index = end
    }
    return result
}

/** 生成结束：静默轮清空 parts；否则把文本合成一段并做 Hermes 清理。 */
internal fun finishReply(message: UIMessage): UIMessage {
    val raw = message.textOrNull() ?: return message
    if (WhaleReplyPipeline.isSilent(raw)) {
        // 静默：清空本轮 parts，finishGeneration 会把节点删掉，界面和通知都不留痕
        return message.copy(parts = emptyList())
    }
    val cleaned = WhaleReplyPipeline.clean(raw)
    if (cleaned.isBlank()) return message.copy(parts = emptyList())
    return message.replaceText(cleaned)
}

/** 流式显示：做同样的清理，并藏掉还没写完的最后一行。 */
internal fun previewReply(message: UIMessage): UIMessage {
    val raw = message.textOrNull() ?: return message
    if (WhaleReplyPipeline.isSilent(raw)) return message.copy(parts = emptyList())
    return message.replaceText(streamingDisplay(raw))
}

/**
 * 流式显示：先做同样的清理，再把"还没写完的最后一行"藏起来。
 * 规则见任务书 1.5：最后一行以 `(`、`（`、`[SILENT` 开头就先不显示（多半是正在输出的括号动作或静默标记）。
 */
internal fun streamingDisplay(raw: String): String {
    val cleaned = WhaleReplyPipeline.cleanForDisplay(raw)
    val lines = cleaned.split("\n")
    if (lines.size < 2) {
        val only = cleaned.trimStart()
        return if (only.startsWith("(") || only.startsWith("（") || only.startsWith("[SILENT")) "" else cleaned
    }
    val last = lines.last().trimStart()
    val dropLast = last.startsWith("(") || last.startsWith("（") || last.startsWith("[SILENT")
    return if (dropLast) lines.dropLast(1).joinToString("\n") else cleaned
}

// ------------------------------------------------------------------ 小工具

private fun kotlinx.serialization.json.JsonElement?.asText(): String =
    runCatching { (this as kotlinx.serialization.json.JsonPrimitive).content }.getOrDefault("")

internal fun UIMessage.textOrNull(): String? =
    parts.filterIsInstance<UIMessagePart.Text>()
        .joinToString("\n") { it.text }
        .takeIf { it.isNotBlank() }

/** 把所有 Text 部件合成一个（放在第一个 Text 的位置），其余部件顺序不动。 */
private fun UIMessage.replaceText(text: String): UIMessage {
    var replaced = false
    val newParts = parts.mapNotNull { part ->
        if (part is UIMessagePart.Text) {
            if (replaced) {
                null
            } else {
                replaced = true
                UIMessagePart.Text(text)
            }
        } else {
            part
        }
    }
    return copy(parts = newParts)
}
