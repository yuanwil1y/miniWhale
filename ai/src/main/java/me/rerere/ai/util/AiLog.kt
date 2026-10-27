package me.rerere.ai.util

import android.util.Log
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart

/**
 * :ai 模块唯一的日志出口（N-001）。
 *
 * - 默认**关闭**：release 包里 :ai 不往 logcat 写任何东西。app 启动时按 `BuildConfig.DEBUG` 打开（`RikkaHubApp`）。
 * - 打开时也**只打元信息**：消息条数、字符数、模型、耗时、状态码、异常类型。不打请求体、SSE 块、错误响应体、
 *   图片地址和异常 message —— 这些都可能带着聊天内容（系统提示词、记忆、历史、她的回复）。
 * - 消息用 lambda 传进来，关闭时连字符串都不拼。
 *
 * 想看完整请求，用 debug 包的 OkHttp 日志（只到 HEADERS）或高级设置 → 日志（`RequestLoggingInterceptor`）。
 */
object AiLog {
    @Volatile
    var enabled: Boolean = false

    /** 测试用：替换真正的输出（JVM 单测里 android.util.Log 不可用）。 */
    @Volatile
    @PublishedApi
    internal var sink: (priority: Int, tag: String, message: String) -> Unit = { priority, tag, message ->
        Log.println(priority, tag, message)
    }

    inline fun d(tag: String, message: () -> String) = log(Log.DEBUG, tag, message)

    inline fun i(tag: String, message: () -> String) = log(Log.INFO, tag, message)

    inline fun w(tag: String, message: () -> String) = log(Log.WARN, tag, message)

    @PublishedApi
    internal inline fun log(priority: Int, tag: String, message: () -> String) {
        if (enabled) sink(priority, tag, message())
    }
}

/** 一组消息的元信息：条数、各角色条数、文字总字符数、图片数，不含任何正文。 */
fun List<UIMessage>.logSummary(): String {
    var chars = 0
    var images = 0
    var tools = 0
    forEach { message ->
        message.parts.forEach { part ->
            when (part) {
                is UIMessagePart.Text -> chars += part.text.length
                is UIMessagePart.Reasoning -> chars += part.reasoning.length
                is UIMessagePart.Image -> images++
                is UIMessagePart.Tool -> tools++
                else -> {}
            }
        }
    }
    return "messages=$size chars=$chars images=$images toolParts=$tools"
}

/** 异常只记类型，不记 message（服务端错误信息里可能回显请求内容）。 */
fun Throwable?.logType(): String = this?.javaClass?.name ?: "null"
