package whaleui.nightsky.ltd.alarm

import android.util.Log
import me.rerere.rikkahub.service.ChatService
import whaleui.nightsky.ltd.whale.WhaleAlarmTurn
import whaleui.nightsky.ltd.whale.WhaleBootstrap
import whaleui.nightsky.ltd.whale.WhaleConversationProvider

/**
 * [AlarmTurnRunner] 的真实实现（任务书 6.1）。
 *
 * 只做三件事：拿到固定对话 id、让 `ChatService.runAlarmTurn` 开一轮、把结果翻译成调度层要的结果。
 *
 * 并发：闹钟轮和用户发消息共用同一份会话锁。用户正在等回复时 `ChatService.runAlarmTurn` 返回 `Busy`，
 * 这里原样交回调度层，由调度层**用闹钟重新登记**顺延（W-023），进程被冻结 / 杀掉也不会丢。
 */
class WhaleAlarmTurnRunner(
    private val chatService: ChatService,
    private val conversations: WhaleConversationProvider,
    private val bootstrap: WhaleBootstrap,
) : AlarmTurnRunner {

    override suspend fun runAlarmTurn(alarmNote: String, taskId: String?): AlarmTurnOutcome {
        // W-009：闹钟把进程冷启动时，先等首次初始化跑完（SOUL / 她的工具 / Key 缓存），最多等 15 秒
        if (!bootstrap.awaitReady()) Log.w(TAG, "bootstrap not ready, run alarm turn anyway")
        val conversationId = conversations.fixedConversation().id
        val alarm = WhaleAlarmTurn(note = alarmNote, taskId = taskId)
        return when (chatService.runAlarmTurn(conversationId, alarm)) {
            ChatService.AlarmTurnResult.Sent -> AlarmTurnOutcome.Sent
            ChatService.AlarmTurnResult.Silent -> AlarmTurnOutcome.Silent
            ChatService.AlarmTurnResult.Busy -> AlarmTurnOutcome.Busy
            ChatService.AlarmTurnResult.Failed -> AlarmTurnOutcome.Failed
        }
    }

    override suspend fun hasRepliedSince(sinceMillis: Long): Boolean {
        val conversation = chatService.getConversation(conversations.fixedConversation().id) ?: return false
        return conversation.currentMessages.hasVisibleReplySince(sinceMillis)
    }

    private companion object {
        const val TAG = "WhaleAlarmTurnRunner"
    }
}
