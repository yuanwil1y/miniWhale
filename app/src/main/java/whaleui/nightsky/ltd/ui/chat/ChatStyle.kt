package whaleui.nightsky.ltd.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import whaleui.nightsky.ltd.ui.theme.WhaleRadius
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// ---- 气泡几何（A，02 §2.1）----
internal val TailGutter = 8.dp
internal val BubbleRadius = WhaleRadius.bubble
internal val GroupedRadius = WhaleRadius.bubbleSmall
/** 尾巴从外侧边线弯出去的高度（自定：Telegram 用 arcTo 画，没有单独的高度值，按截图取 10dp） */
internal val BubbleTailHeight = 10.dp
/** 气泡阴影 = 同形路径下移 1dp */
internal val BubbleShadowOffset = 1.dp
/** 文字最大宽 = 父宽 − 80dp；气泡宽 = 文字宽 + 31dp（左右内边距 + 尾巴区） */
internal val BubbleTextInset = 80.dp
internal val BubbleExtraWidth = 31.dp
/** 文字起点：我 = 路径左 + 11dp；她 = 路径左 + 17dp（含 8dp 尾巴区）。右侧补齐到 31dp */
internal val BubblePadOutStart = 11.dp
internal val BubblePadOutEnd = 20.dp
internal val BubblePadInStart = 17.dp
internal val BubblePadInEnd = 14.dp
internal val BubblePadTop = 8.dp
internal val BubblePadBottom = 8.dp
/** 时间和最后一行之间至少留的空 */
internal val BubbleTimeGap = 8.dp
/** 她的气泡路径左缘距屏幕 3dp；我的贴右 */
internal val BubbleEdgeIn = 3.dp
/**
 * 组内 / 组间间距（自定）：Telegram 是 1dp / 3dp，但它有尾巴阴影和群头像撑开层次；
 * 我们私聊没头像，3dp 组间看不出断开，取 2dp / 6dp。
 */
internal val BubbleGapInGroup = 2.dp
internal val BubbleGapBetweenGroups = 6.dp
/** 新消息进入：轻微上滑的距离（自定，简单版，不做 Telegram 原样弹入） */
internal val MessageEnterLift = 16.dp
/** 和最后一行同行时，时间比文字底再往下沉一点，基线对齐观感（自定，按截图调） */
internal val BubbleTimeDrop = 2.dp

// ---- 日期胶囊 / 表情 / 媒体（u2 §2.5–§3）----
/** 胶囊左右 10dp（自定）、上下 3dp */
internal val ServicePillHorizontal = 10.dp
internal val ServicePillVertical = 3.dp
/** 表情消息距屏幕边 11dp（无尾巴区） */
internal val StickerEdge = 11.dp
/** 图片宽上限 300dp；高 / 宽 限制在 0.5–1.33（自定），读不出尺寸时按 0.75 */
internal val ImageMaxWidth = 300.dp
internal const val ImageMinRatio = 0.5f
internal const val ImageMaxRatio = 1.33f
internal const val ImageDefaultRatio = 0.75f
/** 语音：内容宽 = 120dp + 秒数 × 6dp，至少 180dp（自定）；播放钮 44dp */
internal val VoiceBaseWidth = 120.dp
internal val VoicePerSecond = 6.dp
internal val VoiceMinWidth = 180.dp
internal val VoiceButton = 44.dp
/** 文件气泡整宽 240dp（现值保留），内容宽扣掉 31dp */
internal val DocumentWidth = 240.dp - BubbleExtraWidth

@Immutable
internal data class ChatTextStyles(
    val message: TextStyle,
    val name: TextStyle,
    val status: TextStyle,
    val date: TextStyle,
    val time: TextStyle,
    val input: TextStyle,
    val duration: TextStyle,
    val imageTime: TextStyle,
)

/** 聊天页用到的字体，全部来自 [WhaleTheme.type]（字重调整在主题里统一处理）。 */
@Composable
internal fun rememberChatTextStyles(): ChatTextStyles {
    val type = WhaleTheme.type
    return remember(type) {
        ChatTextStyles(
            message = type.message,
            name = type.chatName,
            status = type.chatStatus,
            date = type.service,
            time = type.messageTime,
            input = type.input,
            duration = type.messageTime,
            imageTime = type.messageTime,
        )
    }
}

/**
 * 毛玻璃样式。haze 2.x 把 1.x 的 HazeStyle/HazeTint 换成了 HazeBlurStyle + colorEffects：
 * - 叠色（原来的 tint）→ colorEffects 里一条 HazeColorEffect.tint(...)
 * - 不支持模糊时的兜底（原来的 fallbackTint）→ fallbackColorEffect
 * 数值保持与 1.x 时完全一致：blurRadius 20dp、noise 0、叠色 glassTint。
 */
@Composable
internal fun rememberGlassStyle(): HazeBlurStyle {
    val colors = WhaleTheme.colors
    return remember(colors) {
        HazeBlurStyle {
            blurRadius(20.dp)
            noiseFactor(0f)
            colorEffects(listOf(HazeColorEffect.tint(colors.surfaceBarGlass)))
            fallbackColorEffect(HazeColorEffect.tint(colors.surfaceBar))
        }
    }
}

/** 毛玻璃：Haze 模糊 + 70% 叠色；Preview / 不支持模糊时退回半透明块。 */
internal fun Modifier.glass(
    shape: Shape,
    state: HazeState,
    style: HazeBlurStyle,
    inspection: Boolean,
    fallback: Color,
    stroke: Color,
): Modifier = this
    .clip(shape)
    .then(
        if (inspection) {
            Modifier.background(fallback)
        } else {
            Modifier.hazeBlur(input = HazeInput.Sources(state), style = style)
        },
    )
    .border(0.5.dp, stroke, shape)

internal fun formatDuration(seconds: Int): String {
    val minutes = seconds / 60
    val remaining = seconds % 60
    return "$minutes:${remaining.toString().padStart(2, '0')}"
}

/** 空聊天服务卡（u2 §8）：宽 240、头像 64。 */
internal val EmptyCardWidth: Dp = 240.dp
internal val EmptyAvatar: Dp = 64.dp
