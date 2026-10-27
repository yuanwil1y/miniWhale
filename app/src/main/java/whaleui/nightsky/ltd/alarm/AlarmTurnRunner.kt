package whaleui.nightsky.ltd.alarm

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import whaleui.nightsky.ltd.data.hasVisibleContent

/** 一次闹钟轮的结果（调度层据此决定标记已触发、写 lastSentAt 还是用闹钟顺延）。 */
enum class AlarmTurnOutcome {
    /** 真的发出去了 */
    Sent,

    /** 开了一轮，她选择静默 */
    Silent,

    /** 他的消息正在回，没开这一轮：调度层用闹钟顺延（W-023） */
    Busy,

    /** 开了一轮但生成失败：调度层稍后重试 */
    Failed,
}

/**
 * 「助手发起一轮」的执行入口，由 Lead 在 ChatService 侧实现。
 *
 * 调度层（[WhaleAlarmScheduler] / [WhaleHerAlarmScheduler] / [WhaleAlarmReceiver] / [WhaleAlarmWorker]）只负责
 * 「什么时候该触发」，具体怎么开一轮（新回复节点、prompt 注入、通知、静默判断）都在这个实现里。
 */
interface AlarmTurnRunner {

    /**
     * 触发一次闹钟轮，等这一轮结束才返回。
     *
     * @param alarmNote 这条任务给她的提示（`ScheduleTask.hint`），没有提示时为空串。
     */
    suspend fun runAlarmTurn(alarmNote: String, taskId: String? = null): AlarmTurnOutcome

    /**
     * 从 [sinceMillis] 起她有没有发出过看得见的消息。
     *
     * 进程在一轮生成中途被杀时，看门狗闹钟到点后用它判断上一次到底发没发出去：
     * 发出去了就只补记状态，不再重复发一次（W-007）。
     */
    suspend fun hasRepliedSince(sinceMillis: Long): Boolean = false
}

/** [sinceMillis] 之后（含）有没有她发的、界面上看得见的消息。 */
internal fun List<UIMessage>.hasVisibleReplySince(
    sinceMillis: Long,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): Boolean = any { message ->
    message.role == MessageRole.ASSISTANT &&
        message.createdAt.toInstant(zone).toEpochMilliseconds() >= sinceMillis &&
        message.hasVisibleContent()
}
