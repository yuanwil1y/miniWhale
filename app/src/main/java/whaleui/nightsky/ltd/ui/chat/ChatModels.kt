package whaleui.nightsky.ltd.ui.chat

import androidx.compose.runtime.Immutable
import whaleui.nightsky.ltd.domain.chat.ChatMessage
import whaleui.nightsky.ltd.domain.chat.PendingAttachment
import whaleui.nightsky.ltd.domain.chat.ReplyPreview

/** 语音播放状态，整个聊天页同一时间只播一条。 */
@Immutable
data class VoicePlayback(
    val messageId: Long? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
) {
    fun progressFor(message: ChatMessage): Float {
        if (message.id != messageId || durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}

/** 整页状态。组件只读它，不直接改它。 */
@Immutable
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val draft: String = "",
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val isHerTyping: Boolean = false,
    /** 顶栏输入指示的文案：正在输入… / 正在录音… / 正在发送图片…（null 用默认文案） */
    val typingLabel: String? = null,
    val replyTo: ReplyPreview? = null,
    val voice: VoicePlayback = VoicePlayback(),
    /** 一次性的提示（附件太大、格式不支持……），界面弹 Toast 后调 consumeNotice 清掉 */
    val notice: String? = null,
    val isLoadingOlder: Boolean = false,
    val hasOlder: Boolean = true,
    val isInitialLoading: Boolean = false,
)

