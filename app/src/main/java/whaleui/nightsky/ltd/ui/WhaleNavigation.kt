package whaleui.nightsky.ltd.ui

import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith

// ---------------------------------------------------------------------------
// 页面切换：页面 id、切页动画、返回键回到哪一页（R1 / W-051 从 WhaleRoot 拆出来）
// ---------------------------------------------------------------------------

internal const val ScreenChat = "chat"
internal const val ScreenMemory = "memory"
internal const val ScreenSchedule = "schedule"
internal const val ScreenSettings = "settings"
internal const val ScreenPermissions = "permissions"
internal const val ScreenStickers = "stickers"
internal const val ScreenAdvanced = "advanced"
/** 她的资料页（B）：从聊天顶栏头像 / 名字进，返回回聊天 */
internal const val ScreenProfile = "profile"
/** 编辑她的名字和头像：从资料页顶栏的铅笔进，返回 / 保存回资料页 */
internal const val ScreenProfileEdit = "profileEdit"

/** ActionBarLayout 的非 preview 转场：150ms，DecelerateInterpolator(1.5)（WhaleMotion.page）。 */
private const val PageTransitionMs = WhaleDuration.Fast

/** 切页动画：新页从右侧 [offsetPx] 处淡入滑入，旧页向左淡出滑出。 */
internal fun whalePageTransition(offsetPx: Int): ContentTransform =
    (
        fadeIn(tween(PageTransitionMs, easing = TgEasing.Decelerate15)) +
            slideInHorizontally(
                animationSpec = tween(PageTransitionMs, easing = TgEasing.Decelerate15),
                initialOffsetX = { offsetPx },
            )
        ) togetherWith (
        fadeOut(tween(PageTransitionMs, easing = TgEasing.Decelerate15)) +
            slideOutHorizontally(
                animationSpec = tween(PageTransitionMs, easing = TgEasing.Decelerate15),
                targetOffsetX = { -offsetPx },
            )
        )

/** 系统返回键在非聊天页时回到哪一页（高级设置、以及从资料页进的设置 / 权限页单独处理：从哪里进来回哪里）。 */
internal fun backTargetOf(screen: String): String = when (screen) {
    ScreenPermissions, ScreenStickers -> ScreenSettings
    ScreenProfileEdit -> ScreenProfile
    else -> ScreenChat
}
