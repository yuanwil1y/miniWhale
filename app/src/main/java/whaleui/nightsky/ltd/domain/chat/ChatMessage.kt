package whaleui.nightsky.ltd.domain.chat

import androidx.compose.runtime.Immutable

// 聊天消息的领域模型（R1 / W-050 从 ui.chat 搬来）

/**
 * 消息发送状态。界面上对应：时钟 → 单勾 → 双勾。
 * 只对我的消息有意义；她的消息固定为 Read。
 */
enum class MessageStatus {
    Sending,
    Sent,
    Read,
}

/** 引用/回复的摘要。真正发送时只带这段文本，不复制整条消息。 */
@Immutable
data class ReplyPreview(
    val messageId: Long,
    val author: String,
    val text: String,
)

/**
 * 一条表情消息（round5）。表情没有气泡背景，单独一套渲染（见 `ChatStickers.kt`）。
 *
 * [url] 为 null 表示这张表情已经从库里删掉了：聊天里保留位置，显示「表情已删除」占位。
 */
@Immutable
data class StickerMessage(
    /** 对外编号，如 `01-1000001`。 */
    val id: String,
    /** 本地 `file://` 路径；null = 表情已删除。 */
    val url: String?,
    /** series.json 里的 description（≤12 字），会话列表/通知预览用。 */
    val description: String,
    /** series.json 里的 detail（30–90 字），请求里他发的表情用这一条。 */
    val detail: String = "",
) {
    val isDeleted: Boolean get() = url == null
}

/**
 * 一条聊天消息。
 *
 * 图片既可以是本机 content:// / file:// Uri，也可以是 Preview 里的占位 key
 * （"sunset" / "street"），由 UI 决定怎么画。
 */
@Immutable
data class ChatMessage(
    val id: Long,
    val fromHer: Boolean,
    val text: String? = null,
    val imageUris: List<String> = emptyList(),
    val voiceSeconds: Int? = null,
    /** 语音文件的本地路径（file:// 或绝对路径）；阶段 7 起是真实合成的 mp3。 */
    val voiceUrl: String? = null,
    val waveform: List<Float> = emptyList(),
    /** 这条语音转语音之前的文字（text_to_speech 存在 Audio 部件 metadata 的 text 里）；语音丢失时显示它（D5） */
    val voiceText: String? = null,
    /** 语音文件读不出来（不在了 / 坏了）：气泡显示 [voiceText] 和「语音已丢失」，不播放（W-042） */
    val voiceLost: Boolean = false,
    val documentName: String? = null,
    val documentSize: Long? = null,
    /** 文档在 App 私有目录里的 `file://`（选中时已复制进 `files/whale/uploads/`，F5） */
    val documentUrl: String? = null,
    /** 文档的真实 mime（取不到时按扩展名推断），决定用哪个解析器 */
    val documentMime: String? = null,
    val time: String,
    val dayLabel: String = "今天",
    /** 发出时间（毫秒，库里 `UIMessage.createdAt` 换算来的）；只给界面判断「隔了 5 分钟另起一组」。0 = 不知道 */
    val createdAtMillis: Long = 0L,
    val status: MessageStatus = MessageStatus.Read,
    val replyTo: ReplyPreview? = null,
    /** 表情消息（round5）：不为 null 时这条消息按「无气泡表情」渲染。 */
    val sticker: StickerMessage? = null,
    /**
     * 库里一条消息拆成多个气泡时（他一次发几张图 / 几个文件 / 图配文），这个气泡所属那条消息的界面 id。
     * 删这个气泡只删它那部分（[ChatRepository.deleteMessagePart]），不能整条删。
     */
    val partOf: Long? = null,
) {
    val isImage: Boolean get() = imageUris.isNotEmpty()
    val isVoice: Boolean get() = voiceSeconds != null
    val isDocument: Boolean get() = documentName != null
    val isSticker: Boolean get() = sticker != null

    /** 列表里的摘要，用于回复条、菜单、无障碍描述。 */
    val previewText: String
        get() = when {
            sticker != null -> if (sticker.isDeleted) "表情已删除" else sticker.description
            text != null && text.isNotBlank() -> text
            isImage -> if (imageUris.size > 1) "[图片 ×${imageUris.size}]" else "[图片]"
            isVoice -> "[语音]"
            isDocument -> documentName.orEmpty()
            else -> ""
        }
}

/** 待发送附件。id 用 Uri 字符串，保证删除/动画的 key 稳定。 */
@Immutable
data class PendingAttachment(
    val id: String,
    val uri: String,
    val name: String,
    val mimeType: String?,
    val size: Long,
) {
    val isImage: Boolean get() = mimeType?.startsWith("image/") == true
}
