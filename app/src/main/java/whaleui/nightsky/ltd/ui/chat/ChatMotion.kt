package whaleui.nightsky.ltd.ui.chat

/**
 * 聊天页专用的动效小工具。曲线和时长统一在 ui/theme/WhaleMotion.kt（TgEasing / WhaleDuration），这里只留发送过渡的一条规则：
 * ui/TextMessageEnterTransition.java：气泡背景 / 文字在过渡前 40% 完成淡入。
 */
object ChatMotion {
    private const val FLIGHT_FADE_PORTION = 0.4f

    fun flightAlpha(raw: Float): Float = (raw / FLIGHT_FADE_PORTION).coerceIn(0f, 1f)
}
