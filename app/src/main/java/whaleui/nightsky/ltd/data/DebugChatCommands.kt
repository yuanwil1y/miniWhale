package whaleui.nightsky.ltd.data

import me.rerere.ai.ui.UIMessagePart

/**
 * 聊天输入框里的调试命令（只有 debug 包有）。
 *
 * 实现只在 src/debug（`whaleDebugChatCommands`）；release 等其它构建类型编译 src/nodebug 里的空实现，
 * 命令解析、示例语音、直接跑工具的代码都不进包（R2，沿用 R1 / W-051 的 debug / nodebug 源集）。
 * [RikkaChatRepository] 只负责把调试命令产出的这一轮一次性写进会话、推给界面。
 */
interface DebugChatCommands {
    /** 这段输入是不是调试命令；是的话他的消息不交给模型 */
    fun isCommand(rawText: String): Boolean

    /** 调试命令要写进会话的她这一轮；[rawText] 不是调试命令时返回 null */
    suspend fun reply(rawText: String): DebugTurn?
}

class DebugTurn(
    /** 她的回复，每个元素一个气泡（一个节点） */
    val bubbles: List<List<UIMessagePart>>,
    /** 像真的回复一样标已读、显示「正在输入」（假回复命令） */
    val actLikeReply: Boolean,
)
