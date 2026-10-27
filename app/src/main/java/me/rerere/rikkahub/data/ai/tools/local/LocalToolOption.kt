package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 助手设置里的本地工具开关。R2 删掉了全部原生本地工具（她的 localTools 恒为空），
 * 这些类型只为老设置 JSON 里的值（如 {"type":"screen_time"}、{"type":"calendar"}）还能读进来、原样写回。
 */
@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    // S10：:speech 已删，朗读工具不再提供；保留这个类型只为老助手设置里的 {"type":"tts"} 还能读
    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("chart_display")
    data object ChartDisplay : LocalToolOption()
}
