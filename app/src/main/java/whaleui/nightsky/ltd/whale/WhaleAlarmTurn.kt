package whaleui.nightsky.ltd.whale

/**
 * 这一轮是闹钟轮（任务书 4.3 / 6.1）：她自己找他，而不是回他的消息。
 *
 * 跟着这一轮的请求走（`ChatTurnContext.whaleAlarm` → `TransformerContext.whaleAlarm`），
 * 普通轮为 null。以前用的是进程级全局标记 `WhaleTurnState`，两个闹钟同一分钟触发、
 * 或闹钟和他的消息交错时会互相覆盖（W-012），所以改成随请求传递。
 *
 * @property note 闹钟说明（`ScheduleTask.hint` 或她自己定闹钟时写的 note），附在请求末尾
 * @property taskId 触发这一轮的任务 id（种子任务用稳定 id，用来识别「睡前那一轮」，不匹配标题文字）；
 *   她自己定的一次性闹钟没有任务 id
 */
data class WhaleAlarmTurn(
    val note: String,
    val taskId: String? = null,
)
