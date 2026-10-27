package whaleui.nightsky.ltd.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleAlpha
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleMotion
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme
import whaleui.nightsky.ltd.ui.theme.rememberWhaleHaptics

// ---------------------------------------------------------------------------
// 开关 / 按钮 / 图标按钮（U0，02 §2.3 / §4.1 / §7）
// ---------------------------------------------------------------------------

private val SwitchTrackWidth = 31.dp
private val SwitchTrackHeight = 14.dp
private val SwitchThumbRadius = 10.dp
private val SwitchTravel = 17.dp

/**
 * Telegram Switch：轨道 31×14 r7、拇指 r10、行程 17dp、200ms AccelerateDecelerate；切换带一次触感。
 * 深色下拇指 = 卡片底色，外圈一道轨道色描边（Switch.java 的画法）。触控区 48dp。
 * [onCheckedChange] 为 null 时只展示（不可点、不变暗），给外层整行 toggleable 用。
 */
@Composable
fun WhaleSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val colors = WhaleTheme.colors
    val haptics = rememberWhaleHaptics()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = WhaleMotion.toggle(),
        label = "switch",
    )
    val track by animateColorAsState(
        targetValue = if (checked) colors.switchTrackOn else colors.switchTrackOff,
        animationSpec = WhaleMotion.toggle(),
        label = "switchTrack",
    )
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            haptics.toggle(it)
            onCheckedChange(it)
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(WhaleSpacing.touchTarget)
            .then(toggle)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(width = SwitchTrackWidth + 6.dp, height = SwitchThumbRadius * 2)) {
            val trackW = SwitchTrackWidth.toPx()
            val trackH = SwitchTrackHeight.toPx()
            val left = (size.width - trackW) / 2f
            val top = (size.height - trackH) / 2f
            drawRoundRect(
                color = track,
                topLeft = Offset(left, top),
                size = Size(trackW, trackH),
                cornerRadius = CornerRadius(trackH / 2f),
            )
            val r = SwitchThumbRadius.toPx() * 0.9f
            val startX = left + trackH / 2f - 1.dp.toPx()
            val cx = startX + SwitchTravel.toPx() * progress
            val center = Offset(cx, size.height / 2f)
            drawCircle(color = colors.switchThumb, radius = r, center = center)
            drawCircle(color = track, radius = r - 1.dp.toPx(), center = center, style = Stroke(2.dp.toPx()))
        }
    }
}

enum class WhaleButtonStyle { Primary, Secondary, Danger, Text }

/**
 * 按钮四种：主（实心 accentFill）/ 次（描边）/ 危险（实心 danger）/ 文字（对话框按钮样式）。
 * 高 48（文字按钮 40）、胶囊；按下 80ms 缩到 0.96，松手弹簧回弹（specs README §2.2）；禁用 50%。
 * [loading] 时文字换成 16dp 小圈，宽度不变。
 */
@Composable
fun WhaleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WhaleButtonStyle = WhaleButtonStyle.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
    /** 文字 / 次按钮的字色（次按钮连描边一起）；默认 accent，危险操作传 danger */
    textColor: Color? = null,
    compact: Boolean = false,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = if (pressed) tween(WhaleDuration.Press) else WhaleMotion.pressRelease(),
        label = "buttonPress",
    )
    val (container, content, border) = when (style) {
        WhaleButtonStyle.Primary -> Triple(colors.accentFill, colors.onAccent, Color.Transparent)
        WhaleButtonStyle.Secondary -> Triple(Color.Transparent, textColor ?: colors.accent, textColor ?: colors.accent)
        WhaleButtonStyle.Danger -> Triple(colors.danger, colors.onAccent, Color.Transparent)
        WhaleButtonStyle.Text -> Triple(Color.Transparent, textColor ?: colors.accent, Color.Transparent)
    }
    val height: Dp = when {
        style == WhaleButtonStyle.Text || compact -> WhaleSpacing.dialogButtonHeight
        else -> WhaleSpacing.buttonHeight
    }
    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .defaultMinSize(minWidth = 64.dp)
            .height(height)
            .clip(WhaleShapes.pill)
            .background(container)
            .then(if (style == WhaleButtonStyle.Secondary) Modifier.border(1.dp, border, WhaleShapes.pill) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = ripple(),
                enabled = enabled && !loading,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled)
            .padding(horizontal = if (style == WhaleButtonStyle.Text) WhaleSpacing.m else WhaleSpacing.xl),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.alpha(if (loading) WhaleAlpha.Hidden else WhaleAlpha.Visible),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(WhaleSpacing.s))
                }
                Text(text = text, style = if (compact) type.buttonSmall else type.button, color = content, maxLines = 1)
            }
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = content,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * 图标按钮：视觉图标 24dp、触控 48dp、圆形 ripple（顶栏用 rippleBar 色由主题 ripple 兜底）；
 * 按下缩到 0.9（Telegram ScaleStateListAnimator）。
 */
@Composable
fun WhaleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = WhaleTheme.colors.textPrimary,
    enabled: Boolean = true,
    iconSize: Dp = WhaleSpacing.icon,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) WhaleAlpha.PressedScaleStrong else 1f,
        animationSpec = if (pressed) tween(WhaleDuration.Press, easing = TgEasing.EaseOut) else WhaleMotion.pressRelease(),
        label = "iconPress",
    )
    Box(
        modifier = modifier
            .size(WhaleSpacing.touchTarget)
            .clip(WhaleShapes.circle)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = true),
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .alpha(if (enabled) WhaleAlpha.Visible else WhaleAlpha.Disabled),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** 只显示一个图标（不可点）：颜色、尺寸由调用方定；默认 24dp。 */
@Composable
fun WhaleIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = WhaleSpacing.icon,
    contentDescription: String? = null,
) {
    Icon(icon, contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}
