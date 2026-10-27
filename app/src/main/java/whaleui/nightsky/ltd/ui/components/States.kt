package whaleui.nightsky.ltd.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import whaleui.nightsky.ltd.ui.theme.TgEasing
import whaleui.nightsky.ltd.ui.theme.WhaleDuration
import whaleui.nightsky.ltd.ui.theme.WhaleShapes
import whaleui.nightsky.ltd.ui.theme.WhaleSpacing
import whaleui.nightsky.ltd.ui.theme.WhaleTheme

// ---------------------------------------------------------------------------
// 加载 / 空 / 错误（U0，02 §2.5 / §5.5；FlickerLoadingView、StickerEmptyView 的数值）
// ---------------------------------------------------------------------------

/** 骨架扫光：带宽 200dp、色标 0/0.4/0.6/1、一轮约 800ms、线性。 */
fun Modifier.shimmer(): Modifier = composed {
    val colors = WhaleTheme.colors
    val bandPx = with(LocalDensity.current) { 200.dp.toPx() }
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(
        initialValue = -bandPx,
        targetValue = bandPx * 4,
        animationSpec = infiniteRepeatable(tween(WhaleDuration.Shimmer, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX",
    )
    drawWithContent {
        drawContent()
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.4f to colors.skeletonHighlight,
                0.6f to colors.skeletonHighlight,
                1f to Color.Transparent,
                start = Offset(x, 0f),
                end = Offset(x + bandPx, 0f),
            ),
        )
    }
}

/** 一条骨架：高 8、r4。 */
@Composable
fun SkeletonBar(width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(width)
            .height(WhaleSpacing.s)
            .clip(WhaleShapes.skeletonBar)
            .background(WhaleTheme.colors.skeleton),
    )
}

/** 列表首屏骨架：[rows] 行「圆头像 r25 + 72dp 条 + 192dp 条」，文字起点 76dp。 */
@Composable
fun Skeleton(rows: Int = 6, modifier: Modifier = Modifier, withAvatar: Boolean = true) {
    val colors = WhaleTheme.colors
    Column(modifier.fillMaxWidth().shimmer()) {
        repeat(rows) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(horizontal = WhaleSpacing.l),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (withAvatar) {
                    Box(Modifier.size(50.dp).clip(WhaleShapes.circle).background(colors.skeleton))
                    Spacer(Modifier.width(76.dp - WhaleSpacing.l - 50.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(WhaleSpacing.m)) {
                    SkeletonBar(72.dp)
                    SkeletonBar(192.dp)
                }
            }
        }
    }
}

/** 出现：150ms 缩放 0.8→1 + 淡入（StickerEmptyView）。 */
@Composable
private fun AppearScale(content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { p.animateTo(1f, tween(WhaleDuration.Fast, easing = TgEasing.Default)) }
    Box(
        Modifier.graphicsLayer {
            alpha = p.value
            scaleX = 0.8f + 0.2f * p.value
            scaleY = 0.8f + 0.2f * p.value
        },
    ) { content() }
}

/** 空状态：图标 + 标题 20 Medium + 副标题 14 textSecondary + 可选按钮（48dp，左右 28）。 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    text: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = WhaleTheme.colors
    val type = WhaleTheme.type
    AppearScale {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = WhaleSpacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(WhaleSpacing.m))
            }
            Text(text = title, style = type.emptyTitle, color = colors.textPrimary, textAlign = TextAlign.Center)
            if (text != null) {
                Spacer(Modifier.height(WhaleSpacing.s))
                Text(text = text, style = type.emptyText, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(WhaleSpacing.xl))
                WhaleButton(text = actionLabel, onClick = onAction, modifier = Modifier.padding(horizontal = 28.dp - WhaleSpacing.xl))
            }
        }
    }
}

/** 整页拿不到数据：标题 + 原因 + 「重试」。 */
@Composable
fun ErrorState(
    title: String,
    reason: String?,
    onRetry: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        title = title,
        text = reason,
        icon = WhaleIcons.Alert,
        actionLabel = if (onRetry != null) "重试" else null,
        onAction = onRetry,
        modifier = modifier,
    )
}
