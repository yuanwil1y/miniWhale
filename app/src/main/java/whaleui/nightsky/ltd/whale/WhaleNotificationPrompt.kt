package whaleui.nightsky.ltd.whale

/**
 * 首次进聊天页时要不要自动申请通知权限（W-019）。
 *
 * 只在 Android 13+（有运行时通知权限）、通知没开、而且以前没自动问过时问一次；
 * 被拒后不再反复弹（之后从「权限与后台常驻」页手动开）。
 */
object WhaleNotificationPrompt {
    /** Build.VERSION_CODES.TIRAMISU */
    private const val TIRAMISU = 33

    fun shouldAutoAsk(sdkInt: Int, notificationsEnabled: Boolean, alreadyAsked: Boolean): Boolean =
        sdkInt >= TIRAMISU && !notificationsEnabled && !alreadyAsked
}
