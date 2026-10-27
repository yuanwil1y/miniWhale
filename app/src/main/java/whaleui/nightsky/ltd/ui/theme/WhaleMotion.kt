package whaleui.nightsky.ltd.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.TweenSpec
import kotlin.math.pow

// ---------------------------------------------------------------------------
// 动效 token（U0，02-telegram-design.md §4.0 / §4.1）
// 曲线与时长只在这里定义；页面里不再写 tween(数字)。
// ---------------------------------------------------------------------------

/** Telegram 的插值器（CubicBezierInterpolator 等），控制点照抄数值。 */
object TgEasing {
    /** DEFAULT (0.25, 0.1, 0.25, 1) = CSS ease */
    val Default: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    /** EASE_OUT (0, 0, 0.58, 1) */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    /** EASE_OUT_QUINT (0.23, 1, 0.32, 1)：Telegram 13 的主力曲线 */
    val EaseOutQuint: Easing = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
    /** EASE_IN (0.42, 0, 1, 1) */
    val EaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)
    /** EASE_BOTH (0.42, 0, 0.58, 1) */
    val EaseBoth: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    /** EASE_OUT_BACK (0.34, 1.56, 0.64, 1)：回弹 */
    val EaseOutBack: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
    /** ChatListItemAnimator.DEFAULT_INTERPOLATOR = 键盘曲线 */
    val ChatList: Easing = CubicBezierEasing(0.19919473f, 0.010644531f, 0.27920937f, 0.9102539f)
    /** DecelerateInterpolator() = 1-(1-t)² */
    val Decelerate: Easing = Easing { 1f - (1f - it) * (1f - it) }
    /** DecelerateInterpolator(1.5f) = 1-(1-t)³（页面切换） */
    val Decelerate15: Easing = Easing { 1f - (1f - it).pow(3) }
    /** AccelerateDecelerateInterpolator（开关） */
    val AccelerateDecelerate: Easing = Easing { (kotlin.math.cos((it + 1) * Math.PI) / 2.0 + 0.5).toFloat() }
    val Linear: Easing = LinearEasing

    /** OvershootInterpolator(tension)：(t+1)x³ + t·x² + 1，x = t-1 */
    fun overshoot(tension: Float = 2f): Easing = Easing {
        val x = it - 1f
        x * x * ((tension + 1f) * x + tension) + 1f
    }

    /** 发送飞入的横向曲线：EASE_OUT(EASE_OUT_QUINT(t)) */
    fun flight(raw: Float): Float = EaseOut.transform(EaseOutQuint.transform(raw.coerceIn(0f, 1f)))
}

/** 时长（毫秒）。名字按用途，数值见 02 §4.1。 */
object WhaleDuration {
    /** 按钮按下缩放（ScaleStateListAnimator） */
    const val Press = 80
    /** 删除淡出（ChatListItemAnimator） */
    const val Dismiss = 120
    /** 页面切换、普通菜单底、日期胶囊淡入淡出、空状态出现 */
    const val Fast = 150
    /** 菜单项依次出现 / 关闭弹层（BottomSheet 关闭 180） */
    const val Short = 180
    /** 开关、复选框、长按菜单缩放 */
    const val Toggle = 200
    /** 发送按钮 / 语音飞入 */
    const val Send = 220
    /** 聊天列表、键盘 ↔ 面板、标签指示条 */
    const val Medium = 250
    /** 滚到底按钮 */
    const val ScrollButton = 280
    /** 抽屉松手、预测返回取消 */
    const val Drawer = 300
    /** 长按压暗 */
    const val Scrim = 320
    /** 文字展开 / 收起（CollapseTextCell） */
    const val Expand = 340
    /** FAB 出现 / 隐藏 */
    const val Fab = 380
    /** 底部弹层打开 */
    const val Sheet = 400
    /** 顶栏标题淡入 */
    const val Title = 420
    /** 点击标签切页（ViewPagerFixed） */
    const val TabPage = 540
    /** 骨架屏扫光一轮 */
    const val Shimmer = 800
    /** 试听中的跳动条一轮（自定，没有 Telegram 对应） */
    const val Loop = 900

    /** 正在输入三点：单点放大 320 + 缩小 320，周期 800，三点错开 150 */
    const val TypingDotHalf = 320
    const val TypingDotPeriod = 800
    const val TypingDotStagger = 150

    /** 日期胶囊停止滚动后延迟淡出 */
    const val ServiceHideDelay = 500L
    /** 长按触发 */
    const val LongPress = 400L

    /** Bulletin 停留：<20 字 1500ms，否则 2750ms；带撤销 5000ms（Bulletin.java / BulletinFactory） */
    const val BulletinShort = 1500L
    const val BulletinLong = 2750L
    const val BulletinUndo = 5000L
}

object WhaleMotion {
    /** 页面进入 / 返回：150ms Decelerate(1.5) */
    fun <T> page(): TweenSpec<T> = tween(WhaleDuration.Fast, easing = TgEasing.Decelerate15)
    /** 聊天列表 / 新消息 / 键盘：250ms ChatList */
    fun <T> chatList(): TweenSpec<T> = tween(WhaleDuration.Medium, easing = TgEasing.ChatList)
    /** 底部弹层打开 400ms EaseOutQuint */
    fun <T> sheetOpen(): TweenSpec<T> = tween(WhaleDuration.Sheet, easing = TgEasing.EaseOutQuint)
    /** 底部弹层关闭 180ms EaseOut */
    fun <T> sheetClose(): TweenSpec<T> = tween(WhaleDuration.Short, easing = TgEasing.EaseOut)
    /** 开关 200ms */
    fun <T> toggle(): TweenSpec<T> = tween(WhaleDuration.Toggle, easing = TgEasing.AccelerateDecelerate)
    /** 普通淡入淡出 150ms */
    fun <T> fade(): TweenSpec<T> = tween(WhaleDuration.Fast, easing = TgEasing.Linear)
    /** 长按 / 弹层压暗 320ms EaseOutQuint */
    fun <T> scrim(): TweenSpec<T> = tween(WhaleDuration.Scrim, easing = TgEasing.EaseOutQuint)
    /** FAB 380ms EaseOutQuint */
    fun <T> fab(): TweenSpec<T> = tween(WhaleDuration.Fab, easing = TgEasing.EaseOutQuint)
    /** 展开 / 收起 340ms EaseOutQuint */
    fun <T> expand(): TweenSpec<T> = tween(WhaleDuration.Expand, easing = TgEasing.EaseOutQuint)
    /** 删除淡出 120ms */
    fun <T> dismiss(): TweenSpec<T> = tween(WhaleDuration.Dismiss, easing = TgEasing.ChatList)
    /** 菜单项依次出现 180ms Decelerate */
    fun <T> menuItem(): TweenSpec<T> = tween(WhaleDuration.Short, easing = TgEasing.Decelerate)
    /** 抽屉松手 300ms EaseOutQuint */
    fun <T> drawer(): TweenSpec<T> = tween(WhaleDuration.Drawer, easing = TgEasing.EaseOutQuint)

    /** Bulletin 进出：弹簧 dampingRatio 0.8 / stiffness 400（Bulletin.java） */
    fun <T> bulletin(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 400f)
    /** 按钮松开回弹（Overshoot 1.5 的近似） */
    fun <T> pressRelease(): SpringSpec<T> = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)

    /** 普通弹出菜单的背景展开时长：150 + 16 × 项数（ActionBarPopupWindow） */
    fun menuOpenMs(items: Int): Int = WhaleDuration.Fast + 16 * items
    /** 菜单第 i 项的出现延迟 */
    fun menuItemDelayMs(index: Int): Int = 16 * index
}
