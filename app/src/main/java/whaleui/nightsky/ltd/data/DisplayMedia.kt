package whaleui.nightsky.ltd.data

import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import whaleui.nightsky.ltd.sticker.firstStickerPart

/**
 * 一条消息里**能显示**的媒体。
 *
 * 关键点：`text_to_speech` / `draw` 的结果不是直接放在 assistant 消息的顶层，
 * 而是嵌在 `UIMessagePart.Tool.output` 里（工具调用 → 工具结果）。如果只看顶层部件，
 * 语音条和图片气泡就都不会出现（实测：MiniMax 返回了 200、mp3 也落盘了，但界面上什么都没有）。
 * 这里把顶层和工具输出里的媒体合并起来，`RikkaChatRepository.toChatMessage` 用它决定要不要出气泡。
 */
internal data class DisplayMedia(
    val images: List<String> = emptyList(),
    val audio: UIMessagePart.Audio? = null,
) {
    val hasAny: Boolean get() = images.isNotEmpty() || audio != null
}

internal fun collectDisplayMedia(parts: List<UIMessagePart>): DisplayMedia {
    val topImages = parts.filterIsInstance<UIMessagePart.Image>().map { it.url }
    val topAudio = parts.filterIsInstance<UIMessagePart.Audio>().firstOrNull()
    val toolOutputs = parts.filterIsInstance<UIMessagePart.Tool>().flatMap { it.output }
    val toolImages = toolOutputs.filterIsInstance<UIMessagePart.Image>().map { it.url }
    val toolAudio = toolOutputs.filterIsInstance<UIMessagePart.Audio>().firstOrNull()
    return DisplayMedia(
        images = topImages + toolImages,
        audio = topAudio ?: toolAudio,
    )
}
/**
 * 这条消息在聊天界面上会不会出气泡：有文字、文档、表情，或者顶层 / 工具输出里有图片、语音。
 * 只有工具调用或只有思考内容的消息不出气泡。`RikkaChatRepository.toChatMessage` 和闹钟轮判断
 * 「她这一轮有没有真的发出东西」用的是同一个标准。
 */
fun UIMessage.hasVisibleContent(): Boolean =
    parts.any { (it is UIMessagePart.Text && it.text.isNotBlank()) || it is UIMessagePart.Document } ||
        collectDisplayMedia(parts).hasAny ||
        firstStickerPart() != null
