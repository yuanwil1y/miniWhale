package whaleui.nightsky.ltd.prompt

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.BuildConfig

/**
 * **仅 debug** 的请求日志（任务书 4.4）。
 *
 * 把「实际发给模型的最终 messages」的**结构**打到 logcat，用来核对注入位置。只打数字，不打任何正文（W-057）：
 * - 每条消息只打角色、文字长度、part 数和非文字 part 的类型；
 * - 每轮注入那几段是固定文案，也不打正文，只打「标记出现的位置」：看到 `now@1234 reminder@1290` 这种
 *   就说明报时、REMINDER 都在，而且顺序对。**位置是相对该条消息的字符下标。**
 * - 不打印任何 Key（messages 里本来也没有 key，这里也不碰 header / 设置）。
 *
 * 以前 user 消息 ≤600 字、其它消息 ≤120 字会整段打出来，长正文打前 30 字 —— 这会把他说的话、她的回复、
 * SOUL / 记忆的开头带进 logcat / bugreport，W-057 起全部去掉。高级设置 → 日志页不读这里（它读 `Logging`），不受影响。
 *
 * release 构建里 [log] 第一行就返回（`BuildConfig.DEBUG` 是编译期常量，R8 会把整段删掉）。
 */
object WhaleRequestLog {

    private const val TAG = "WhaleRequest"

    /**
     * 每轮注入的固定标记，用来核对「有没有 / 在什么位置」。
     * 这些字符串都来自 Hermes 逐字照抄的资产文件，本身不是隐私内容；日志里也只出现左边的标签名。
     */
    private val MARKERS: List<Pair<String, String>> = listOf(
        "now" to "[现在 ",
        "reminder" to "（这段是你心里的小提醒",
        "voice" to "（回之前想一下",
        "antirepeat" to "（你最近几次是这样开头的",
        "cron" to "（这次是你自己的闹钟响了",
        "alarmnote" to "（闹钟：",
        "silent" to "[SILENT]",
        "native_time" to "<time_reminder>",
        "skill_list" to "use_skill",
    )

    fun log(messages: List<UIMessage>, tag: String = "") {
        if (!BuildConfig.DEBUG) return
        runCatching { lines(messages, tag).forEach { Log.i(TAG, it) } }
    }

    /** 要打的每一行。只含数字、角色名、part 类型名和标记标签，不含消息正文。纯函数，便于单测。 */
    internal fun lines(messages: List<UIMessage>, tag: String = ""): List<String> = buildList {
        val head = if (tag.isBlank()) "" else "[$tag] "
        add("${head}request messages = ${messages.size}")
        messages.forEachIndexed { index, message ->
            val text = message.parts.filterIsInstance<UIMessagePart.Text>().joinToString("\n") { it.text }
            val others = message.parts.filterNot { it is UIMessagePart.Text }
                .joinToString(",") { it::class.simpleName.orEmpty() }
            add(
                "[$index] role=${message.role.name} len=${text.length} parts=${message.parts.size}" +
                    if (others.isEmpty()) "" else " others=[$others]",
            )
            val marks = markSummary(text)
            if (marks.isNotEmpty()) add("[$index] marks: $marks")
        }
    }

    /** `label@offset` 列表；没出现的不列。纯函数，便于单测。 */
    internal fun markSummary(text: String): String {
        val found = MARKERS.mapNotNull { (label, needle) ->
            val at = text.indexOf(needle)
            if (at >= 0) "$label@$at" else null
        }
        return found.joinToString(" ")
    }
}
