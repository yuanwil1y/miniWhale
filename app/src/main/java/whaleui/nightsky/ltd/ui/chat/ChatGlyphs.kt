package whaleui.nightsky.ltd.ui.chat

import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.components.WhaleIcon
import whaleui.nightsky.ltd.ui.components.WhaleIcons
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.domain.chat.MessageStatus


/** 播放三角和暂停双条之间的形变；progress 0 = 播放，1 = 暂停。 */
@Composable
internal fun PlayPauseGlyph(modifier: Modifier, color: Color, progress: Float) {
    Canvas(modifier) {
        val p = progress.coerceIn(0f, 1f)
        val triangle = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.08f)
            lineTo(size.width * 0.92f, size.height * 0.5f)
            lineTo(size.width * 0.18f, size.height * 0.92f)
            close()
        }
        drawPath(triangle, color.copy(alpha = (1f - p).coerceIn(0f, 1f)))
        if (p > 0.01f) {
            val barWidth = size.width * 0.16f
            val gap = size.width * 0.14f
            val top = size.height * 0.11f
            val height = size.height * 0.78f * (0.55f + 0.45f * p)
            val left = size.width / 2f - gap / 2f - barWidth
            drawRoundRect(
                color = color.copy(alpha = p),
                topLeft = Offset(left, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            drawRoundRect(
                color = color.copy(alpha = p),
                topLeft = Offset(size.width / 2f + gap / 2f, (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}


/** 状态图标切换：时钟 → 单勾 → 双勾，缩放 + 淡入淡出。 */
@Composable
internal fun MessageStatusGlyph(
    status: MessageStatus,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = status,
            transitionSpec = {
                (fadeIn(tween(WhaleDuration.Fast, easing = TgEasing.Default)) + scaleIn(
                    initialScale = 0.5f,
                    animationSpec = tween(WhaleDuration.Fast, easing = TgEasing.Default),
                )) togetherWith (
                    fadeOut(tween(WhaleDuration.Press)) +
                        scaleOut(
                            targetScale = 0.5f,
                            animationSpec = tween(WhaleDuration.Press, easing = TgEasing.Default),
                        )
                    )
            },
            label = "messageStatus",
        ) { state ->
            when (state) {
                MessageStatus.Sending -> WhaleIcon(WhaleIcons.Clock, tint = color, modifier = Modifier.size(16.dp))
                MessageStatus.Sent -> WhaleIcon(WhaleIcons.Check, tint = color, modifier = Modifier.size(16.dp))
                MessageStatus.Read -> WhaleIcon(WhaleIcons.CheckCheck, tint = color, modifier = Modifier.size(16.dp))
            }
        }
    }
}


