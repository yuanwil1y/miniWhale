package whaleui.nightsky.ltd.debug

import android.content.Context
import me.rerere.ai.core.Tool
import whaleui.nightsky.ltd.data.DebugChatCommands
import whaleui.nightsky.ltd.data.DebugTurn

// 非 debug 构建用的空实现：没有任何调试命令，以斜杠开头的输入照常当作他的消息发出去。
// 真正的实现在 src/debug/…/WhaleDebugChatCommands.kt；两份签名必须一致。

@Suppress("UNUSED_PARAMETER")
fun whaleDebugChatCommands(context: Context, toolFinder: suspend (name: String) -> Tool?): DebugChatCommands =
    NoDebugChatCommands

private object NoDebugChatCommands : DebugChatCommands {
    override fun isCommand(rawText: String): Boolean = false

    override suspend fun reply(rawText: String): DebugTurn? = null
}
