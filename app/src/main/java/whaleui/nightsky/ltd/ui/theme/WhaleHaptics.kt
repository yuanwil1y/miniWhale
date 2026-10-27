package whaleui.nightsky.ltd.ui.theme

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

// ---------------------------------------------------------------------------
// 触感（U0，02-telegram-design.md §6.2 / §7）
// Telegram 只用三种：长按（LONG_PRESS）、小确认（KEYBOARD_TAP：发送、录音、切换）、错误；
// 开关自带一次。普通点击、滚动不震。
// ---------------------------------------------------------------------------

@Stable
class WhaleHaptics internal constructor(private val view: View) {
    /** 长按列表项 / 消息 */
    fun longPress() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** 发送、录音开始 / 锁定、切换类小确认 */
    fun tap() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 开关切换（Switch.java 内置） */
    fun toggle(on: Boolean) {
        val type = if (android.os.Build.VERSION.SDK_INT >= 34) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(type)
    }

    /** 输入不合法、操作失败（APP_ERROR） */
    fun error() {
        val type = if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        view.performHapticFeedback(type)
    }
}

@Composable
fun rememberWhaleHaptics(): WhaleHaptics {
    val view = LocalView.current
    return remember(view) { WhaleHaptics(view) }
}
