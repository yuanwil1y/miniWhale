package me.rerere.rikkahub.data.event

import kotlin.uuid.Uuid

sealed class AppEvent {
    /**
     * 聊天生成结束（完成、失败或取消）。
     * [contentPreview] 为 null 时不发送完成通知。
     */
    data class ChatGenerationEnded(
        val conversationId: Uuid,
        val senderName: String,
        val contentPreview: String?,
    ) : AppEvent()
}
